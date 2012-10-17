package org.apache.vysper.xmpp.modules.extension.mobile_device_metadata;

import org.apache.vysper.storage.StorageProvider;

import java.util.List;

public interface MobileDeviceMetadataStorageProvider extends StorageProvider {

    public List<Integer> getBuildVersionsForUser(String bareJID);

    public List<String> getPlatformVersionsForUser(String bareJID);

}