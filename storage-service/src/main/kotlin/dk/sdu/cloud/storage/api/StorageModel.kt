/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.api

import com.fasterxml.jackson.annotation.JsonIgnore

enum class AccessRight {
    READ,
    WRITE,
    EXECUTE
}

data class MetadataEntry(val key: String, val value: String)
typealias Metadata = List<MetadataEntry>

data class AccessEntry(val entity: String, val isGroup: Boolean, val rights: Set<AccessRight>)
typealias AccessControlList = List<AccessEntry>

enum class FileType {
    FILE,
    DIRECTORY,
    LINK
}

data class StorageFile(
    val type: FileType,
    val path: String,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val ownerName: String,
    val size: Long = 0,
    val acl: List<AccessEntry> = emptyList(),
    val favorited: Boolean = false,
    val sensitivityLevel: SensitivityLevel = SensitivityLevel.CONFIDENTIAL,
    val link: Boolean = false,
    val annotations: Set<String> = emptySet(),
    @get:JsonIgnore val inode: Long = 0
)

enum class SensitivityLevel {
    OPEN_ACCESS,
    CONFIDENTIAL,
    SENSITIVE
}
