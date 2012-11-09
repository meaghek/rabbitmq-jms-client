package com.rabbitmq.jms.client;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.jms.IllegalStateException;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.QueueReceiver;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicSubscriber;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.jms.admin.RMQDestination;
import com.rabbitmq.jms.util.Abortable;
import com.rabbitmq.jms.util.EntryExitManager;
import com.rabbitmq.jms.util.RMQJMSException;
import com.rabbitmq.jms.util.TimeTracker;
import com.rabbitmq.jms.util.Util;

/**
 * The implementation of {@link MessageConsumer} in the RabbitMQ JMS Client.
 * <p>
 * Single message {@link #receive receive()}s are implemented with a special buffer and {@link Consumer}.
 * </p>
 * <p>
 * {@link MessageListener#onMessage} calls are implemented with a more conventional {@link Consumer}.
 * </p>
 */
public class RMQMessageConsumer implements MessageConsumer, QueueReceiver, TopicSubscriber {
    private static final int DEFAULT_BATCHING_SIZE = 5;
    private static final long STOP_TIMEOUT_MS = 1000; // ONE SECOND
    /**
     * The destination that this consumer belongs to
     */
    private final RMQDestination destination;
    /**
     * The session that this consumer was created under
     */
    private final RMQSession session;
    /**
     * Unique tag, used when creating AMQP queues for a consumer that thinks it's a topic
     */
    private final String uuidTag;
    /**
     * The {@link Consumer} that we use to subscribe to Rabbit messages which drives {@link MessageListener#onMessage}.
     */
    private final AtomicReference<MessageListenerConsumer> listenerConsumer =
                                                                              new AtomicReference<MessageListenerConsumer>();
    /**
     * Entry and exit of application threads calling {@link #receive} are managed by this.
     * @see javax.jms.Connection#start()
     * @see javax.jms.Connection#stop()
     */
    private final EntryExitManager receiveManager = new EntryExitManager();
    /**
     * We track things that need to be aborted (for a Connection.close()). Typically these are waits.
     */
    private final AbortableHolder abortables = new AbortableHolder();
    /**
     * Is this consumer closed? This value can change to true, but never changes back.
     */
    private volatile boolean closed = false;
    /**
     * If this consumer is in the process of closing.
     */
    private volatile boolean closing = false;
    /**
     * {@link MessageListener}, set by the user.
     */
    private volatile MessageListener messageListener;
    /**
     * Flag to check if we are a durable subscription.
     */
    private volatile boolean durable = false;
    /**
     * Flag to check if we have noLocal set
     */
    private volatile boolean noLocal = false;
    /** Buffer for messages on {@link #receive} queues, but not yet handed to application. */
    private final ReceiveBuffer receiveBuffer;

    /**
     * Creates a RMQMessageConsumer object. Internal constructor used by {@link RMQSession}
     *
     * @param session - the session object that created this consume
     * @param destination - the destination for this consumer
     * @param uuidTag - when creating queues to a topic, we need a unique queue name for each consumer. This is the
     *            unique name.
     * @param paused - true if the connection is {@link javax.jms.Connection#stop}ped, false otherwise.
     */
    public RMQMessageConsumer(RMQSession session, RMQDestination destination, String uuidTag, boolean paused) {
        this.session = session;
        this.destination = destination;
        this.uuidTag = uuidTag;
        if (!paused)
            this.receiveManager.openGate();
        this.receiveBuffer = new ReceiveBuffer(DEFAULT_BATCHING_SIZE, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Queue getQueue() throws JMSException {
        return this.destination;
    }

    /**
     * {@inheritDoc} Note: This implementation always returns null
     */
    @Override
    public String getMessageSelector() throws JMSException {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageListener getMessageListener() throws JMSException {
        return this.messageListener;
    }

    /**
     * Dispose of any Rabbit Consumer that may be active and tracked.
     */
    private void removeListenerConsumer() {
        log("removeListenerConsumer");
        MessageListenerConsumer listenerConsumer = this.listenerConsumer.getAndSet(null);
        if (listenerConsumer != null) {
            this.abortables.remove(listenerConsumer);
            listenerConsumer.stop();  // orderly stop
            listenerConsumer.abort(); // force it if it didn't work
        }
    }

    /**
     * From the on-line JavaDoc: <blockquote>
     * <p>
     * Sets the message consumer's {@link MessageListener}.
     * </p>
     * <p>
     * Setting the message listener to <code>null</code> is the equivalent of clearing the message listener for the
     * message consumer.
     * </p>
     * <p>
     * The effect of calling {@link #setMessageListener} while messages are being consumed by an existing listener or
     * the consumer is being used to consume messages synchronously is undefined.
     * </p>
     * </blockquote>
     * <p>
     * Notwithstanding, we attempt to clear the previous listener gracefully (by cancelling the Consumer) if there is
     * one.
     * </p>
     * {@inheritDoc}
     */
    @Override
    public void setMessageListener(MessageListener messageListener) throws JMSException {
        log("setMessageListener", messageListener);
        if (messageListener == this.messageListener) // no change, so do nothing
            return;
        this.removeListenerConsumer();  // if there is any
        this.messageListener = messageListener;
        this.setNewListenerConsumer(messageListener); // if needed
    }

    /**
     * Create a new RabitMQ Consumer, if necessary.
     * @param messageListener to drive from Consumer; no Consumer is created if this is null.
     * @throws IllegalStateException
     */
    private void setNewListenerConsumer(MessageListener messageListener) throws IllegalStateException {
        if (messageListener != null) {
            MessageListenerConsumer mlConsumer =
                                                 new MessageListenerConsumer(
                                                                             this,
                                                                             getSession().getChannel(),
                                                                             messageListener,
                                                                             TimeUnit.MILLISECONDS.toNanos(this.session.getConnection()
                                                                                                                       .getTerminationTimeout()));
            if (this.listenerConsumer.compareAndSet(null, mlConsumer)) {
                this.abortables.add(mlConsumer);
                if (!this.getSession().getConnection().isStopped()) {
                    mlConsumer.start();
                }
            } else {
                mlConsumer.abort();
                throw new IllegalStateException("MessageListener concurrently set on Consumer " + this);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Message receive() throws JMSException {
        log("receive");
        if (this.closed || this.closing)
            throw new IllegalStateException("Consumer is closed or closing.");
        return internalReceive(new TimeTracker());
    }

    /**
     * Receive a single message from the destination, waiting for up to <code>timeout</code> milliseconds if necessary.
     * <p>
     * The JMS 1.1 specification for {@link javax.jms.Connection#stop()} says:
     * </p>
     * <blockquote>
     * <p>
     * When the connection is stopped, delivery to all the connection's message consumers is inhibited: synchronous
     * receives block, and messages are not delivered to message listeners. {@link javax.jms.Connection#stop()} blocks until
     * receives and/or message listeners in progress have completed.
     * </p>
     * </blockquote>
     * <p>
     * For synchronous gets, we potentially have to block on the way in.
     * <p/>
     * {@inheritDoc}
     *
     * @param timeout - (in milliseconds)
     *            <p/>
     *            {@inheritDoc}
     */
    @Override
    public Message receive(long timeout) throws JMSException {
        log("receive", timeout);
        if (this.closed || this.closing)
            throw new IllegalStateException("Consumer is closed or closing.");
        return internalReceive(new TimeTracker(timeout==0?Long.MAX_VALUE:timeout, TimeUnit.MILLISECONDS));
    }

    /**
     * Returns true if messages should be auto acknowledged upon arrival
     *
     * @return true if {@link Session#getAcknowledgeMode()}=={@link Session#DUPS_OK_ACKNOWLEDGE} or
     *         {@link Session#getAcknowledgeMode()}=={@link Session#AUTO_ACKNOWLEDGE}
     */
    boolean isAutoAck() {
        int ackMode = getSession().getAcknowledgeModeNoException();
        return (ackMode == Session.DUPS_OK_ACKNOWLEDGE || ackMode == Session.AUTO_ACKNOWLEDGE);
    }

    /**
     * Register a {@link Consumer} with the Rabbit API to receive messages
     *
     * @param consumer the SynchronousConsumer being registered
     * @throws IOException from RabbitMQ calls
     * @see Channel#basicConsume(String, boolean, String, boolean, boolean, java.util.Map, Consumer)
     */
    public void basicConsume(Consumer consumer) throws IOException {
        String name = rmqQueueName();
        // never ack async messages automatically, only when we can deliver them
        // to the actual consumer so we pass in false as the auto ack mode
        // we must support setMessageListener(null) while messages are arriving
        // and those message we NACK
        getSession().getChannel()
         .basicConsume(name, /* the name of the queue */
                       false, /* autoack is ALWAYS false, otherwise we risk acking messages that are received
                               * to the client but the client listener(onMessage) has not yet been invoked */
                       newConsumerTag(), /* the consumer tag to use */
                       this.getNoLocalNoException(), /* RabbitMQ supports the noLocal flag for subscriptions */
                       false, /* exclusive will always be false: exclusive consumer access true means only this
                               * consumer can access the queue. */
                       null, /* there are no custom arguments for the subscription */
                       consumer /* the callback object for handleDelivery(), etc. */
                       );
    }

    /**
     * RabbitMQ {@link Channel#basicConsume} should accept a {@link null} consumer-tag, to cause it to generate a new,
     * unique one for us; but it doesn't :-(
     */
    static final String newConsumerTag() {
        return "jms-consumer-" + Util.generateUUIDTag();
        // return null;
    }

    String rmqQueueName() {
        if (this.destination.isQueue()) {
            /* javax.jms.Queue we share a single AMQP queue among all consumers hence the name will the the name of the
             * destination */
            return this.destination.getName();
        } else {
            /* javax.jms.Topic we created a unique AMQP queue for each consumer and that name is unique for this
             * consumer alone */
            return this.getUUIDTag();
        }
    }

    private RMQMessage internalReceive(TimeTracker tt)  throws JMSException {
    // Pseudocode:
    //   poll msg-buffer(0)
    //   if msg-buffer had a message return it
    //   else {
    //     initiate async-get // we must try *not* to start more than one at a time
    //     poll msg-buffer(timeout)
    //   }
        GetResponse resp = this.receiveBuffer.get(tt);
        if (resp == null) return null;
        boolean aa = isAutoAck();
        if (aa)
            this.acknowledgeMessage(resp);
        return processMessage(resp, aa);
    }

    private void acknowledgeMessage(GetResponse resp) {
        // acknowledge just this one RabbitMQ message
        try {
            this.getSession().getChannel().basicAck(resp.getEnvelope().getDeliveryTag(), false);
        } catch (IOException e) {
            log("acknowledgeMessage", e, resp);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Message receiveNoWait() throws JMSException {
        log("receiveNoWait");
        if (this.closed || this.closing)
            throw new IllegalStateException("Consumer is closed or closing.");
        return internalReceive(TimeTracker.ZERO);
    }

    /**
     * Converts a {@link GetResponse} to a {@link Message}
     *
     * @param response - the message information from RabbitMQ {@link Channel#basicGet} or via a {@link Consumer}.
     * @param acknowledged - whether the message has been acknowledged.
     * @return the JMS message corresponding to the RabbitMQ message
     * @throws JMSException
     */
    RMQMessage processMessage(GetResponse response, boolean acknowledged) throws JMSException {
        if (response == null) /* return null if the response is null */
            return null;

        try {
            /* Deserialize the message from the byte[] */
            RMQMessage message = RMQMessage.fromMessage(response.getBody());
            /* Received messages contain a reference to their delivery tag this is used in Message.acknowledge */
            message.setRabbitDeliveryTag(response.getEnvelope().getDeliveryTag());
            /* Set a reference to this session this is used in Message.acknowledge */
            message.setSession(getSession());
            /* Set the destination this message was received from */
            message.setJMSDestination(getDestination());
            /* Initially the message is readOnly properties == true until clearProperties has been called readOnly body
             * == true until clearBody has been called */
            message.setReadonly(true);
            /* Set the redelivered flag. we inherit this from the RabbitMQ broker */
            message.setJMSRedelivered(response.getEnvelope().isRedeliver());
            if (!acknowledged) {
                /* If the message has not been acknowledged automatically let the session know so that it can track
                 * unacknowledged messages */
                getSession().unackedMessageReceived(message);
            }
            return message;
        } catch (IOException x) {
            throw new RMQJMSException(x);
        } catch (ClassNotFoundException x) {
            throw new RMQJMSException(x);
        } catch (IllegalAccessException x) {
            throw new RMQJMSException(x);
        } catch (InstantiationException x) {
            throw new RMQJMSException(x);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws JMSException {
        this.getSession().consumerClose(this);
    }

    /**
     * @return <code>true</code> if {@link #close()} has been invoked and the call has completed, or <code>false</code>
     *         if the {@link #close()} has not been called or is in progress
     */
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * Method called internally or by the Session when system is shutting down
     */
    void internalClose() throws JMSException {
        log("internalClose");
        this.closing = true;
        /* If we are stopped, we must break that. This will release all threads waiting on the gate and effectively
         * disable the use of the gate */
        this.receiveManager.closeGate(); // stop any more entering receive region
        this.receiveManager.abortWaiters(); // abort any that arrive now

        /* stop and remove any active subscription - waits for onMessage processing to finish */
        this.removeListenerConsumer();

        this.abortables.abort(); // abort async Consumers of both types that remain

        this.closed = true;
        this.closing = false;
    }

    /**
     * Returns the destination this message consumer is registered with
     *
     * @return the destination this message consumer is registered with
     */
    public RMQDestination getDestination() {
        return this.destination;
    }

    /**
     * Returns the session this consumer was created by
     *
     * @return the session this consumer was created by
     */
    public RMQSession getSession() {
        return this.session;
    }

    /**
     * The unique tag that this consumer holds
     *
     * @return unique tag that this consumer holds
     */
    public String getUUIDTag() {
        return this.uuidTag;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Topic getTopic() throws JMSException {
        if (this.getDestination().isQueue()) {
            throw new JMSException("Destination is of type Queue, not Topic");
        }
        return this.getDestination();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getNoLocal() throws JMSException {
        return getNoLocalNoException();
    }

    /**
     * @see #getNoLocal()
     * @return true if the noLocal variable was set
     */
    public boolean getNoLocalNoException() {
        return this.noLocal;
    }

    /**
     * Stops this consumer from receiving messages. This is called by the session indirectly when
     * {@link javax.jms.Connection#stop()} is invoked. In this implementation, any async consumers will be cancelled,
     * only to be re-subscribed when <code>resume()</code>d.
     *
     * @throws InterruptedException if the thread is interrupted
     */
    public void pause() throws InterruptedException {
        this.receiveManager.closeGate();
        this.receiveManager.waitToClear(new TimeTracker(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        this.abortables.stop();
    }

    /**
     * Resubscribes all async listeners and continues to receive messages
     *
     * @see javax.jms.Connection#start()
     * @throws javax.jms.JMSException if the thread is interrupted
     */
    void resume() throws JMSException {
        log("resume");
        this.abortables.start();
        this.receiveManager.openGate();
    }

    /**
     * @return true if durable
     */
    public boolean isDurable() {
        return this.durable;
    }

    /**
     * Set durable status
     *
     * @param durable
     */
    void setDurable(boolean durable) {
        this.durable = durable;
    }

    /**
     * Configures the no local for this consumer.  This is currently only used when subscribing an async consumer.
     *
     * @param noLocal - true if NACKed
     */
    void setNoLocal(boolean noLocal) {
        this.noLocal = noLocal;
    }

    /**
     * Bag of {@link Abortable}s which is itself an {@link Abortable}.
     */
    private static class AbortableHolder implements Abortable {
        private final java.util.Queue<Abortable> abortableQueue = new ConcurrentLinkedQueue<Abortable>();
        private final boolean[] flags = new boolean[] { false, false, false }; // to prevent infinite regress

        private enum Action {
            ABORT(0) {
                void doit(Abortable a) {
                    a.abort();
                }
            },
            START(1) {
                void doit(Abortable a) {
                    a.start();
                }
            },
            STOP(2) {
                void doit(Abortable a) {
                    a.stop();
                }
            };
            private final int ind;

            Action(int ind) {
                this.ind = ind;
            }

            int index() {
                return this.ind;
            }

            abstract void doit(Abortable a);
        };

        public void add(Abortable a) {
            this.abortableQueue.add(a);
        }

        public void remove(Abortable a) {
            this.abortableQueue.remove(a);
        }

        public void abort() {
            act(Action.ABORT);
        }

        public void start() {
            act(Action.START);
        }

        public void stop() {
            act(Action.STOP);
        }

        private void act(Action action) {
            if (this.flags[action.index()]) return; // prevent infinite
            this.flags[action.index()] = true;      // regress

            Abortable[] as = this.abortableQueue.toArray(new Abortable[this.abortableQueue.size()]);
            for (Abortable a : as) {
                action.doit(a);
            }
            this.flags[action.index()] = false;     // allow multiple invocations
        }
    }

    private static final boolean LOGGING = false;

    private final void log(String s, Exception x, Object c) {
        if (LOGGING)
            log("Exception ("+x+") in "+s, c);
    }

    private final void log(String s, Object c) {
        if (LOGGING)
            log(s+"("+String.valueOf(c)+")");
    }

    private final void log(String s) {
        if (LOGGING)
            System.err.println("--->RMQMessageConsumer("+String.valueOf(this.destination)+"): "+s);
    }
}
