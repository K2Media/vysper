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
package org.apache.vysper.xmpp.modules.servicediscovery.collection;

import org.apache.vysper.xmpp.modules.ServerRuntimeContextService;
import org.apache.vysper.xmpp.modules.servicediscovery.management.*;
import org.apache.vysper.xmpp.protocol.NamespaceURIs;

import java.util.*;

/**
 * on an item or info requests, calls all related listeners and collects what they have to add to the
 * response. compiles the responded infos and items.
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
public class ServiceCollector implements ServerRuntimeContextService, ServiceDiscoveryRequestListenerRegistry {

    private static final Feature DEFAULT_FEATURE = new Feature(NamespaceURIs.XEP0030_SERVICE_DISCOVERY_INFO);

    protected List<InfoRequestListener> infoRequestListeners = new ArrayList<InfoRequestListener>();
    protected List<ServerInfoRequestListener> serverInfoRequestListeners = new ArrayList<ServerInfoRequestListener>();
    protected List<ItemRequestListener> itemRequestListeners = new ArrayList<ItemRequestListener>();

    public void addInfoRequestListener(InfoRequestListener infoRequestListener) {
        infoRequestListeners.add(infoRequestListener);
    }

    public void addServerInfoRequestListener(ServerInfoRequestListener infoRequestListener) {
        serverInfoRequestListeners.add(infoRequestListener);
    }

    public void addItemRequestListener(ItemRequestListener itemRequestListener) {
        itemRequestListeners.add(itemRequestListener);
    }

    /**
     * collect all server feature and identity info from the listeners
     */
    public List<InfoElement> processServerInfoRequest(InfoRequest infoRequest) throws ServiceDiscoveryRequestException {
        // sorted structure, to place all <feature/> after <identity/>
        List<InfoElement> elements = new ArrayList<InfoElement>();
        elements.add(DEFAULT_FEATURE);
        for (ServerInfoRequestListener serverInfoRequestListener : serverInfoRequestListeners) {
            List<InfoElement> elementList = null;
            try {
                elementList = serverInfoRequestListener.getServerInfosFor(infoRequest);
            } catch (ServiceDiscoveryRequestException abortion) {
                throw abortion;
            } catch (Throwable e) {
                continue;
            }
            if (elementList != null) elements.addAll(elementList);
        }
        Collections.sort(elements, new ElementPartitioningComparator());
        return elements;
    }

    /**
     * collect all non-server feature and identity info from the listeners
     */
    public List<InfoElement> processInfoRequest(InfoRequest infoRequest) throws ServiceDiscoveryRequestException {
        // sorted structure, to place all <feature/> after <identity/>
        List<InfoElement> elements = new ArrayList<InfoElement>();
        elements.add(DEFAULT_FEATURE);
        for (InfoRequestListener infoRequestListener : infoRequestListeners) {
            List<InfoElement> elementList = null;
            try {
                elementList = infoRequestListener.getInfosFor(infoRequest);
            } catch (ServiceDiscoveryRequestException abortion) {
                throw abortion;
            } catch (Throwable e) {
                continue;
            }
            if (elementList != null) elements.addAll(elementList);
        }
        Collections.sort(elements, new ElementPartitioningComparator());
        return elements;
    }

    /**
     * collect all item info from the listeners
     */
    public List<Item> processItemRequest(InfoRequest infoRequest) throws ServiceDiscoveryRequestException {
        List<Item> elements = new ArrayList<Item>();
        for (ItemRequestListener itemRequestListener : itemRequestListeners) {
            List<Item> elementList = null;
            try {
                elementList = itemRequestListener.getItemsFor(infoRequest);
            } catch (ServiceDiscoveryRequestException abortion) {
                throw abortion;
            } catch (Throwable e) {
                continue;
            }
            if (elementList != null) elements.addAll(elementList);
        }
        return elements;
    }

    public String getServiceName() {
        return SERVICE_DISCOVERY_REQUEST_LISTENER_REGISTRY;
    }

    static class ElementPartitioningComparator implements Comparator<InfoElement> {
        public int compare(InfoElement o1, InfoElement o2) {
            return o1.getElementClassId().compareTo(o2.getElementClassId());
        }
    }
}