package org.monarch.browser

import android.app.Application
import com.humanware.keysoftsdk.contextmenu.WriteCommandsXmlFileToInternalMemoryStorageExecutor

class BrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registers res/raw/commands.xml with KeySoft so the zoom ovals and D-pads reach the viewer.
        WriteCommandsXmlFileToInternalMemoryStorageExecutor(this).execute()
    }
}
