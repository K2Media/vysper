/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.vysper.xmpp.delivery.failure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
public class DeliveryException extends Exception {
    final Logger logger = LoggerFactory.getLogger(DeliveryException.class);

    public DeliveryException() {
        super();
        logger.error("DeliveryException thrown");
    }

    public DeliveryException(String message) {
        super(message);
        logger.error("DeliveryException thrown: " + message);
    }

    public DeliveryException(String message, Throwable throwable) {
        super(message, throwable);
        logger.error("DeliveryException thrown: " + message);
    }

    public DeliveryException(Throwable throwable) {
        super(throwable);
        logger.error("DeliveryException thrown.");
    }
}
