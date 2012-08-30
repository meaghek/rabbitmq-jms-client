/**
 * Redistribution and use of this software and associated documentation
 * ("Software"), with or without modification, are permitted provided
 * that the following conditions are met:
 *
 * 1. Redistributions of source code must retain copyright
 *    statements and notices.  Redistributions must also contain a
 *    copy of this document.
 *
 * 2. Redistributions in binary form must reproduce the
 *    above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other
 *    materials provided with the distribution.
 *
 * 3. The name "Exolab" must not be used to endorse or promote
 *    products derived from this Software without prior written
 *    permission of Exoffice Technologies.  For written permission,
 *    please contact jima@intalio.com.
 *
 * 4. Products derived from this Software may not be called "Exolab"
 *    nor may "Exolab" appear in their names without prior written
 *    permission of Exoffice Technologies. Exolab is a registered
 *    trademark of Exoffice Technologies.
 *
 * 5. Due credit should be given to the Exolab Project
 *    (http://www.exolab.org/).
 *
 * THIS SOFTWARE IS PROVIDED BY EXOFFICE TECHNOLOGIES AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL
 * EXOFFICE TECHNOLOGIES OR ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Copyright 2001, 2003 (C) Exoffice Technologies Inc. All Rights Reserved.
 *
 * $Id: ReceiverCloseTest.java,v 1.4 2003/05/04 14:12:38 tanderson Exp $
 */
package org.exolab.jmscts.test.session;

import java.util.List;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.Session;

import junit.framework.Test;

import org.exolab.jmscts.core.AbstractSendReceiveTestCase;
import org.exolab.jmscts.core.AckTypes;
import org.exolab.jmscts.core.DelayedAction;
import org.exolab.jmscts.core.DeliveryTypes;
import org.exolab.jmscts.core.JMSTestRunner;
import org.exolab.jmscts.core.MessageReceiver;
import org.exolab.jmscts.core.MessageTypes;
import org.exolab.jmscts.core.SessionHelper;
import org.exolab.jmscts.core.TestContext;
import org.exolab.jmscts.core.TestCreator;


/**
 * This class tests the behaviour of closing a session while a receiver is 
 * active. This covers requirements:
 * <ul>
 *   <li>session.close.receivers</li>
 * </ul>
 *
 * @author <a href="mailto:tma@netspace.net.au">Tim Anderson</a>
 * @version $Revision: 1.4 $
 * @see AbstractSendReceiveTestCase
 * @see org.exolab.jmscts.core.SendReceiveTestRunner
 */
public class ReceiverCloseTest extends AbstractSendReceiveTestCase {

    /**
     * Requirements covered by this test case
     */
    private static final String[][] REQUIREMENTS = {
        {"testSessionClose", "session.close.receivers"}};

    /**
     * The destination used by this test case
     */
    private static final String DESTINATION = "ReceiverCloseTest";

    /**
     * Construct an instance of this class for a specific test case.
     * The test will be run against all session types, synchronous delivery
     * types, and using TextMessage messages
     *
     * @param name the name of test case
     */
    public ReceiverCloseTest(String name) {
        super(name, MessageTypes.TEXT, DeliveryTypes.SYNCHRONOUS, 
              REQUIREMENTS);
    }

    /**
     * The main line used to execute this test
     */
    public static void main(String[] args) {
        JMSTestRunner runner = new JMSTestRunner(suite(), args);
        junit.textui.TestRunner.run(runner);
    }

    /**
     * Sets up the test suite
     *
     * @return an instance of this class that may be run by 
     * {@link JMSTestRunner}
     */
    public static Test suite() {
        return TestCreator.createSendReceiveTest(ReceiverCloseTest.class, 
                                                 AckTypes.ALL);
    }

    /**
     * Returns if this test can share resources with other test cases.
     * This implementation always returns <code>false</code>, to 
     * ensure that a new connection is created for each test.
     *
     * @return <code>false</code>
     */
    public boolean share() {
        return false;
    }

    /**
     * Returns if the connection should be started prior to running the test. 
     * This implementation always returns <code>false</code> to avoid
     * conflicts with test cases
     * 
     * @return <code>false</code>
     */
    public boolean startConnection() {
        return false;
    }

    /**
     * Returns the list of destination names used by this test case. These
     * are used to pre-create destinations prior to running the test case.
     *
     * @return the list of destinations used by this test case
     */
    public String[] getDestinations() {
        return new String[]{DESTINATION};
    }
    
    /**
     * Verify that the receive timers for a closed session continue to 
     * advance, so receives may time out and return a null message while the 
     * session is stopped. This covers requirements:
     * <ul>
     *   <li>session.close.receivers</li>
     * </ul>
     *
     * @throws Exception for any error
     */
    public void testSessionClose() throws Exception {
        TestContext context = getContext();
        Connection connection = context.getConnection();
        final Session session = context.getSession();
        Destination destination = getDestination(DESTINATION);
        MessageReceiver receiver = SessionHelper.createReceiver(
            context, destination);

        DelayedAction action = new DelayedAction(1000) {
            public void runProtected() throws Exception {
                session.close();
            }
        };

        try {
            // start the connection
            connection.start();

            // close the session in a separate thread to allow the receiver
            // to invoke receive()
            action.start();

            try {
                // wait indefinitely for a non-existent message
                List result = receiver.receive(1, 5000);
            } catch (Exception exception) {
                fail("Expected synchronous consumer to time out and return " +
                     "null when session closed, but threw exception=" +
                     exception.getClass().getName() + ", message=" +
                     exception.getMessage());
            }

            // receiver returned null. Verify that the connection was stopped
            Exception exception = action.getException();
            if (exception != null) {
                fail("Failed to close session, exception=" +
                     exception.getClass().getName() + ", message=" +
                     exception.getMessage());
            }
        } catch (Exception exception) {
            throw exception;
        } finally {
            try {
                // cannot unsubscribe durable consumers if the session
                // is closed. Closing a consumer for a closed session
                // should be ignored
                receiver.close();
            } catch (Exception exception) {
                fail("Attempting to invoke close() for a consumer on a " +
                     "closed session threw exception=" + 
                     exception.getClass().getName() + ", message=" + 
                     exception.getMessage());
            }
        }
    }
    
} //-- ReceiverCloseTest