package com.github.secp192k1

@Suppress("unused")
internal class InitAttachmentUpload(val files: Array<File>) {
    @Suppress("PropertyName")
    class File(
        val filename: String,
        val file_size: Long,
        val id: String,
    )
}
