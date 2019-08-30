/* Copyright (c) 2013 Pivotal Software, Inc. All rights reserved. */
package com.rabbitmq.integration.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;

public abstract class AbstractITTopicSSL {
    protected TopicConnectionFactory connFactory;
    protected TopicConnection topicConn;

    @BeforeEach
    public void beforeTests() throws Exception {
        this.connFactory =
                (TopicConnectionFactory) AbstractTestConnectionFactory.getTestConnectionFactory(true, 0)
                                                                      .getConnectionFactory();
        this.topicConn = connFactory.createTopicConnection();
    }

    @AfterEach
    public void afterTests() throws Exception {
        if (topicConn != null)
            topicConn.close();
    }

}
