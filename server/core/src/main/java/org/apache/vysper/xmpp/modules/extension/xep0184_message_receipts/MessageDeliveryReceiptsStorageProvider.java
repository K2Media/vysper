package org.apache.vysper.xmpp.modules.extension.xep0184_message_receipts;

import org.apache.vysper.storage.StorageProvider;
import org.apache.vysper.xmpp.stanza.Stanza;

import java.util.List;

public interface MessageDeliveryReceiptsStorageProvider extends StorageProvider {

    public boolean confirmMessageDelivery(String bareJID, List<? extends Stanza> messages);

    public boolean confirmMessageViewed(String bareJID, List<? extends Stanza> messages);

}
