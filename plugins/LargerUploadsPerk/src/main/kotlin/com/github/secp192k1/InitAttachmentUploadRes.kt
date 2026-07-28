package com.github.secp192k1

internal class InitAttachmentUploadRes(val attachments: Array<File>) {
    @Suppress("PropertyName")
    class File(val upload_url: String, val upload_filename: String)
}
