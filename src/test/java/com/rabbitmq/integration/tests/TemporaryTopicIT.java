/* Copyright (c) 2013 Pivotal Software, Inc. All rights reserved. */
package com.rabbitmq.integration.tests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import javax.jms.DeliveryMode;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.jms.TopicSubscriber;

/**
 * Integration test
 */
public class TemporaryTopicIT extends AbstractITTopic {

    private static final String MESSAGE = "Hello " + TemporaryTopicIT.class.getName();

    @Test
    public void testTopicSendAndReceiveSingleSession() throws Exception {
        topicConn.start();
        TopicSession topicSession = topicConn.createTopicSession(false, Session.DUPS_OK_ACKNOWLEDGE);
        Topic topic = topicSession.createTemporaryTopic();
        TopicPublisher topicSender = topicSession.createPublisher(topic);
        topicSender.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

        TopicSubscriber topicReceiver1 = topicSession.createSubscriber(topic);
        TopicSubscriber topicReceiver2 = topicSession.createSubscriber(topic);

        TextMessage message = topicSession.createTextMessage(MESSAGE);
        topicSender.send(message);

        TextMessage message1 = (TextMessage) topicReceiver1.receive(1000);
        TextMessage message2 = (TextMessage) topicReceiver2.receive(1000);
        assertNotNull(message1);
        assertNotNull(message2);
        assertEquals(MESSAGE, message1.getText());
        assertEquals(MESSAGE, message2.getText());
    }

    @Test
    public void testTopicSendAndReceiveTwoSessions() throws Exception {
        topicConn.start();
        TopicSession topicSession1 = topicConn.createTopicSession(false, Session.DUPS_OK_ACKNOWLEDGE);
        TopicSession topicSession2 = topicConn.createTopicSession(false, Session.DUPS_OK_ACKNOWLEDGE);
        Topic topic = topicSession1.createTemporaryTopic();
        TopicPublisher topicSender = topicSession1.createPublisher(topic);
        topicSender.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

        TopicSubscriber topicReceiver1 = topicSession1.createSubscriber(topic);
        TopicSubscriber topicReceiver2 = topicSession2.createSubscriber(topic);

        TextMessage message = topicSession1.createTextMessage(MESSAGE);
        topicSender.send(message);

        TextMessage message1 = (TextMessage) topicReceiver1.receive(1000);
        TextMessage message2 = (TextMessage) topicReceiver2.receive(1000);
        assertNotNull(message1);
        assertNotNull(message2);
        assertEquals(MESSAGE, message1.getText());
        assertEquals(MESSAGE, message2.getText());
    }
}
