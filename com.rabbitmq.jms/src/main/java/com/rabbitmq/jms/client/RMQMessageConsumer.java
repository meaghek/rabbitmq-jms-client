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
import com.rabbitmq.jms.util.AbortedException;
import com.rabbitmq.jms.util.EntryExitManager;
import com.rabbitmq.jms.util.RMQJMSException;
import com.rabbitmq.jms.util.TimeTracker;

public class RMQMessageConsumer implements MessageConsumer, QueueReceiver, TopicSubscriber {
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
     * The async listener that we use to subscribe to Rabbit messages
     */
    private final AtomicReference<MessageListenerConsumer> listenerConsumer =
                                                                              new AtomicReference<MessageListenerConsumer>();
    /**
     * Entry and exit of receive() threads are controlled by this gate. See {@link javax.jms.Connection#start()} and
     * {@link javax.jms.Connection#stop()}
     */
    private final EntryExitManager receiveManager = new EntryExitManager();
    /**
     * We track things that need to be aborted (for a Connection.close()). Typically these are waits.
     */
    private final AbortableHolder abortables = new AbortableHolder();
    /**
     * Is this consumer closed. this value should change to true, but never change back
     */
    private volatile boolean closed = false;
    /**
     * If this consumer is in the process of closing
     */
    private volatile boolean closing = false;
    /**
     * {@link MessageListener}, set by the user*
     */
    private volatile MessageListener messageListener;
    /**
     * Flag to check if we are a durable subscription
     */
    private volatile boolean durable = false;
    /**
     * Flag to check if we have noLocal set
     */
    private volatile boolean noLocal = false;

    /**
     * Creates a RMQMessageConsumer object. Internal constructor used by {@link RMQSession}
     *
     * @param session - the session object that created this consume
     * @param destination - the destination for this consumer
     * @param uuidTag - when creating queues to a topic, we need a unique queue name for each consumer. This is the
     *            unique name.
     */
    public RMQMessageConsumer(RMQSession session, RMQDestination destination, String uuidTag, boolean paused) {
        this.session = session;
        this.destination = destination;
        this.uuidTag = uuidTag;
        if (!paused)
            this.receiveManager.openGate();
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
     * Remove the listener and dispose of any Rabbit Consumer that may be active and tracked.
     */
    private void replaceMessageListener(MessageListener listener) {
        if (listener == this.messageListener)
            return;
        MessageListenerConsumer listenerConsumer = this.listenerConsumer.getAndSet(null);
        if (listenerConsumer != null) {
            this.abortables.remove(listenerConsumer);
            listenerConsumer.stop();  // orderly stop
            listenerConsumer.abort(); // force it if it didn't work
        }
        this.messageListener = listener;
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
        this.replaceMessageListener(messageListener);
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
        return receive(Long.MAX_VALUE);
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
        if (this.closed || this.closing)
            throw new IllegalStateException("Consumer is closed or closing.");
        if (timeout == 0)
            timeout = Long.MAX_VALUE; // The spec identifies 0 as infinite timeout
        TimeTracker tt = new TimeTracker(timeout, TimeUnit.MILLISECONDS);

        Message msg;
        try {
            if (!this.receiveManager.enter(tt))
                return null; // timed out
            /* Try to receive a message synchronously */
            try {
                msg = synchronousGet();
                if (msg != null)
                    return msg;
                if (tt.timeout())
                    return null; // We timed out already. A timeout means we return null to the caller.

                return asynchronousGet(tt);
            } finally {
                this.receiveManager.exit();
            }
        } catch (AbortedException _) {
            /* If the get has been aborted we return null, too. */
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // reset interrupt status
            return null;
        }
    }

    private Message asynchronousGet(TimeTracker tt) throws JMSException, InterruptedException, AbortedException {
        try {
            /* Currently there is no equivalent of receive(timeout) in RabbitMQ's Java API. So we emulate that behaviour
             * by creating a one-shot subscription. We use a SynchronousConsumer - this object supports both timeout and
             * interrupts. */
            /* Create the consumer object - we supply a time tracker */
            SynchronousConsumer sc = new SynchronousConsumer(this.session.getChannel(), tt);

            /* Wait for a message to arrive. This returns null if we timeout. */
            GetResponse response;
            this.abortables.add(sc);
            try {
                /* Subscribe to the consumer object */
                basicConsume(sc);
                response = sc.receive();
            } catch (AbortedException ae) {
                return null;
            } finally {
                this.abortables.remove(sc);
            }
            /* Process the result - even if it is null */
            RMQMessage msg = (RMQMessage) processMessage(response, isAutoAck());
            /* Connection.stop() must not prevent us from returning to the caller if we are still processing this
             * because we have received it from RabbitMQ and may not have acknowledged it. */
            if (msg != null) {
                if (isAutoAck()) {
                    getSession().getChannel().basicAck(msg.getRabbitDeliveryTag(), false);
                }
            }
            return msg;
        } catch (IOException ioe) {
            throw new RMQJMSException(ioe);
        }
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
     * @return the consumer tag created for this consumer
     * @throws IOException from RabbitMQ calls
     * @see Channel#basicConsume(String, boolean, String, boolean, boolean, java.util.Map, Consumer)
     */
    public String basicConsume(Consumer consumer) throws IOException {
        String name = null;
        if (this.destination.isQueue()) {
            /* javax.jms.Queue we share a single AMQP queue among all consumers hence the name will the the name of the
             * destination */
            name = this.destination.getName();
        } else {
            /* javax.jms.Topic we created a unique AMQP queue for each consumer and that name is unique for this
             * consumer alone */
            name = this.getUUIDTag();
        }
        // never ack async messages automatically, only when we can deliver them
        // to the actual consumer so we pass in false as the auto ack mode
        // we must support setMessageListener(null) while messages are arriving
        // and those message we NACK
        return getSession().getChannel()
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

    private static final String newConsumerTag() {
        /* RabbitMQ basicConsumer accepts null, which causes it to generate a new, unique consumer-tag for us */
        /* return "jms-consumer-" + Util.generateUUIDTag(); */
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Message receiveNoWait() throws JMSException {
        if (this.closed || this.closing)
            throw new IllegalStateException("Consumer is closed or closing.");
        return synchronousGet();
    }

    /**
     * @return a received message, or null if no message was received.
     * @throws JMSException
     */
    private Message synchronousGet() throws JMSException {
        GetResponse response = null;
        try {
            if (this.destination.isQueue()) {
                /* For queue, issue a basic.get on the queue name */
                response = this.getSession().getChannel().basicGet(this.destination.getQueueName(), this.isAutoAck());
            } else {
                /* For topic, issue a basic.get on the unique queue name for the consumer */
                response = this.getSession().getChannel().basicGet(this.getUUIDTag(), this.isAutoAck());
            }
        } catch (IOException x) {
            throw new RMQJMSException(x);
        }
        /* convert the message (and remember tag if we need to) */
        return processMessage(response, this.isAutoAck());
    }

    /**
     * Converts a {@link GetResponse} to a {@link Message}
     *
     * @param response
     * @return
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
    protected void internalClose() throws JMSException {
        this.closing = true;
        /* If we are stopped, we must break that. This will release all threads waiting on the gate and effectively
         * disable the use of the gate */
        this.receiveManager.closeGate(); // stop any more entering receive region
        this.receiveManager.abortWaiters(); // abort any that arrive now

        /* remove any subscription that is active at this time - waits for onMessage processing to finish */
        this.replaceMessageListener(null);

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
     * @see #resume()
     */
    public void pause() throws InterruptedException {
        this.receiveManager.closeGate();
        this.receiveManager.waitToClear(new TimeTracker(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        this.abortables.stop();
    }

    /**
     * Resubscribes all async listeners and continues to receive messages
     *
     * @see javax.jms.Connection#stop()
     * @throws javax.jms.JMSException if the thread is interrupted
     */
    public void resume() throws JMSException {
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
    protected void setDurable(boolean durable) {
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
            if (this.flags[action.index()])
                return;
            this.flags[action.index()] = true;
            Abortable[] as = this.abortableQueue.toArray(new Abortable[this.abortableQueue.size()]);
            for (Abortable a : as) {
                action.doit(a);
            }
            this.flags[action.index()] = false;
        }
    }
}
