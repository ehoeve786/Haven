package sh.haven.feature.connections

import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.ConnectionConfig

/**
 * The two enums share names by construction (#137) — defined separately
 * because `core/data` can't depend on `core/ssh`. Convert via name lookup.
 */
internal val ConnectionProfile.addressFamilyForSsh: ConnectionConfig.AddressFamily
    get() = ConnectionConfig.AddressFamily.valueOf(addressFamilyEnum.name)

/**
 * Per-profile reconnect knobs to a value object the SSH session
 * manager understands. Three columns from the data model collapse
 * into one [ConnectionConfig.ReconnectPolicy] — keeps the connect-
 * config builders one line longer instead of three (#150).
 */
internal val ConnectionProfile.reconnectPolicy: ConnectionConfig.ReconnectPolicy
    get() = ConnectionConfig.ReconnectPolicy(
        autoReconnect = autoReconnect,
        maxAttempts = reconnectMaxAttempts,
        onNetworkChange = reconnectOnNetworkChange,
    )
