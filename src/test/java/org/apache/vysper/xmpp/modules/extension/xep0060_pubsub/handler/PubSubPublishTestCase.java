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
package org.apache.vysper.xmpp.modules.extension.xep0060_pubsub.handler;

import org.apache.vysper.xmpp.addressing.Entity;
import org.apache.vysper.xmpp.addressing.EntityImpl;
import org.apache.vysper.xmpp.delivery.StanzaReceiverRelay;
import org.apache.vysper.xmpp.modules.core.base.handler.IQHandler;
import org.apache.vysper.xmpp.modules.extension.xep0060_pubsub.AbstractPublishSubscribeTestCase;
import org.apache.vysper.xmpp.protocol.NamespaceURIs;
import org.apache.vysper.xmpp.protocol.ResponseStanzaContainer;
import org.apache.vysper.xmpp.stanza.IQStanza;
import org.apache.vysper.xmpp.stanza.IQStanzaType;
import org.apache.vysper.xmpp.stanza.StanzaBuilder;
import org.apache.vysper.xmpp.state.resourcebinding.BindException;
import org.apache.vysper.xmpp.xmlfragment.XMLElement;

/**
 * @author The Apache MINA Project (http://mina.apache.org)
 */
public class PubSubPublishTestCase extends AbstractPublishSubscribeTestCase {

	@Override
	protected StanzaBuilder buildInnerElement(StanzaBuilder sb) {
		sb.startInnerElement("publish");
		sb.addAttribute("node", pubsub.getResource());
		sb.startInnerElement("item");
		sb.addText("this is a test");
		sb.endInnerElement();
		sb.endInnerElement();
		return sb;
	}

	@Override
	protected IQHandler getHandler() {
		return new PubSubPublishHandler(root);
	}

	@Override
	protected String getNamespace() {
		return NamespaceURIs.XEP0060_PUBSUB;
	}

	@Override
	protected IQStanzaType getStanzaType() {
		return IQStanzaType.SET;
	}

	public void testPublishResponse() {
		node.subscribe("id", client);
		ResponseStanzaContainer result = sendStanza(getStanza(), true);
		assertTrue(result.hasResponse());
		IQStanza response = new IQStanza(result.getResponseStanza());
		assertEquals(IQStanzaType.RESULT.value(),response.getType());
		
		assertEquals(id, response.getAttributeValue("id")); // IDs must match
		
		// get the subscription Element
		XMLElement pub = response.getFirstInnerElement().getFirstInnerElement();
		XMLElement item = pub.getFirstInnerElement();
		
		assertEquals("publish", pub.getName());
		assertEquals(pubsub.getResource(), pub.getAttributeValue("node"));
		assertNotNull(item); // should be present
		assertNotNull(item.getAttributeValue("id"));
	}
	
	public void testPublishWithSubscriber() throws BindException {
		// create two subscriber for the node
		Entity francisco = createUser("francisco@denmark.lit");
		Entity bernardo = createUser("bernardo@denmark.lit/somewhere");
		
		// subscribe them
		node.subscribe("franid", francisco);
		node.subscribe("bernid", bernardo);
		
		// subscribe the publisher
		node.subscribe("id", client);
		
		assertEquals(3, node.countSubscriptions());
		
		// publish a message
		ResponseStanzaContainer result = sendStanza(getStanza(), true);
		
		// verify response
		assertTrue(result.hasResponse());
		
		// verify that each subscriber received the message
		StanzaReceiverRelay relay = (StanzaReceiverRelay)sessionContext.getServerRuntimeContext().getStanzaRelay();
		assertEquals(3, relay.getCountRelayed()); // three subscribers
	}
	
	protected Entity createUser(String jid) throws BindException {
		String boundResourceId = sessionContext.bindResource();
        Entity usr = new EntityImpl(clientBare, boundResourceId);
        return usr;
	}
}