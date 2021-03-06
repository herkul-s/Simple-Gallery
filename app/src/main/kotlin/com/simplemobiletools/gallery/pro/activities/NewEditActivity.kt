package com.simplemobiletools.gallery.pro.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.util.Pair
import com.simplemobiletools.commons.asynctasks.CopyMoveTask
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.CONFLICT_OVERWRITE
import com.simplemobiletools.commons.helpers.PERMISSION_WRITE_STORAGE
import com.simplemobiletools.commons.helpers.REAL_FILE_PATH
import com.simplemobiletools.commons.interfaces.CopyMoveListener
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.dialogs.SaveAsDialog
import com.simplemobiletools.gallery.pro.extensions.config
import com.simplemobiletools.gallery.pro.extensions.fixDateTaken
import ly.img.android.pesdk.assets.filter.basic.FilterPackBasic
import ly.img.android.pesdk.assets.font.basic.FontPackBasic
import ly.img.android.pesdk.backend.model.config.CropAspectAsset
import ly.img.android.pesdk.backend.model.state.BrushSettings
import ly.img.android.pesdk.backend.model.state.EditorLoadSettings
import ly.img.android.pesdk.backend.model.state.EditorSaveSettings
import ly.img.android.pesdk.backend.model.state.manager.SettingsList
import ly.img.android.pesdk.ui.activity.PhotoEditorBuilder
import ly.img.android.pesdk.ui.model.state.*
import ly.img.android.pesdk.ui.panels.item.CropAspectItem
import ly.img.android.pesdk.ui.panels.item.ToggleAspectItem
import ly.img.android.pesdk.ui.panels.item.ToolItem
import java.io.File
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.collections.set

class NewEditActivity : SimpleActivity() {
    private val PESDK_EDIT_IMAGE = 1
    private val SETTINGS_LIST = "SETTINGS_LIST"
    private val RESULT_IMAGE_PATH = "RESULT_IMAGE_PATH"
    private var sourceFileLastModified = 0L
    private var destinationFilePath = ""
    private var imagePathFromEditor = ""    // delete the file stored at the internal app storage (the editor saves it there) in case moving to the selected location fails
    private var sourceImageUri: Uri? = null

    private lateinit var uri: Uri
    private lateinit var saveUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_edit)

        if (checkAppSideloading()) {
            return
        }

        handlePermission(PERMISSION_WRITE_STORAGE) {
            if (it) {
                initEditActivity()
            } else {
                toast(R.string.no_storage_permissions)
                finish()
            }
        }
    }

    private fun initEditActivity() {
        if (intent.data == null) {
            toast(R.string.invalid_image_path)
            finish()
            return
        }

        uri = intent.data!!
        if (uri.scheme != "file" && uri.scheme != "content") {
            toast(R.string.unknown_file_location)
            finish()
            return
        }

        if (intent.extras?.containsKey(REAL_FILE_PATH) == true) {
            val realPath = intent.extras!!.getString(REAL_FILE_PATH)
            uri = when {
                isPathOnOTG(realPath!!) -> uri
                realPath.startsWith("file:/") -> Uri.parse(realPath)
                else -> Uri.fromFile(File(realPath))
            }
        } else {
            (getRealPathFromURI(uri))?.apply {
                uri = Uri.fromFile(File(this))
            }
        }

        saveUri = when {
            intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true -> intent.extras!!.get(MediaStore.EXTRA_OUTPUT) as Uri
            else -> uri
        }

        openEditor(uri)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == PESDK_EDIT_IMAGE) {
            val extras = resultData?.extras
            imagePathFromEditor = extras?.getString(RESULT_IMAGE_PATH, "") ?: ""

            val settings = extras?.getParcelable<SettingsList>(SETTINGS_LIST)
            if (settings != null) {
                val brush = settings.getSettingsModel(BrushSettings::class.java)
                config.editorBrushColor = brush.brushColor
                config.editorBrushHardness = brush.brushHardness
                config.editorBrushSize = brush.brushSize
            }

            if (resultCode != Activity.RESULT_OK || sourceImageUri == null || sourceImageUri.toString().isEmpty() || imagePathFromEditor.isEmpty() || sourceImageUri.toString() == imagePathFromEditor) {
                toast(R.string.image_editing_failed)
                finish()
            } else {
                // the image is stored at the internal app storage first, for example /data/user/0/com.simplemobiletools.gallery.pro/files/editor/IMG_20191207_183023.jpg
                // first we rename it to the desired name, then move
                val sourceString = Uri.decode(sourceImageUri.toString())?.toString() ?: ""
                val source = if (sourceString.isEmpty() || sourceString.startsWith("content")) {
                    internalStoragePath
                } else {
                    sourceString.substringAfter("file://")
                }

                SaveAsDialog(this, source, true, cancelCallback = {
                    toast(R.string.image_editing_failed)
                    finish()
                }, callback = {
                    destinationFilePath = it
                    handleSAFDialog(destinationFilePath) {
                        if (it) {
                            sourceFileLastModified = File(source).lastModified()
                            val newFile = File("${imagePathFromEditor.getParentPath()}/${destinationFilePath.getFilenameFromPath()}")
                            File(imagePathFromEditor).renameTo(newFile)
                            val sourceFile = FileDirItem(newFile.absolutePath, newFile.name)

                            val conflictResolutions = LinkedHashMap<String, Int>()
                            conflictResolutions[destinationFilePath] = CONFLICT_OVERWRITE

                            val pair = Pair(arrayListOf(sourceFile), destinationFilePath.getParentPath())
                            CopyMoveTask(this, false, true, conflictResolutions, editCopyMoveListener, true).execute(pair)
                        } else {
                            toast(R.string.image_editing_failed)
                            File(imagePathFromEditor).delete()
                            finish()
                        }
                    }
                })
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private val editCopyMoveListener = object : CopyMoveListener {
        override fun copySucceeded(copyOnly: Boolean, copiedAll: Boolean, destinationPath: String) {
            if (config.keepLastModified) {
                // add 1 s to the last modified time to properly update the thumbnail
                updateLastModified(destinationFilePath, sourceFileLastModified + 1000)
            }

            val paths = arrayListOf(destinationFilePath)
            rescanPaths(paths) {
                fixDateTaken(paths, false)
            }

            setResult(Activity.RESULT_OK, intent)
            toast(R.string.file_edited_successfully)
            finish()
        }

        override fun copyFailed() {
            toast(R.string.unknown_error_occurred)
            File(imagePathFromEditor).delete()
            finish()
        }
    }

    private fun openEditor(inputImage: Uri) {
        sourceImageUri = inputImage
        val filename = inputImage.toString().getFilenameFromPath()
        val settingsList = createPesdkSettingsList(filename)

        settingsList.getSettingsModel(EditorLoadSettings::class.java).imageSource = sourceImageUri

        PhotoEditorBuilder(this)
                .setSettingsList(settingsList)
                .startActivityForResult(this, PESDK_EDIT_IMAGE)
    }

    private fun createPesdkSettingsList(filename: String): SettingsList {
        val settingsList = SettingsList()
        settingsList.config.getAssetMap(CropAspectAsset::class.java).apply {
            add(CropAspectAsset("my_crop_1_2", 1, 2, false))
            add(CropAspectAsset("my_crop_2_1", 2, 1, false))
            add(CropAspectAsset("my_crop_19_9", 19, 9, false))
            add(CropAspectAsset("my_crop_9_19", 9, 19, false))
            add(CropAspectAsset("my_crop_5_4", 5, 4, false))
            add(CropAspectAsset("my_crop_4_5", 4, 5, false))
            add(CropAspectAsset("my_crop_37_18", 37, 18, false))
            add(CropAspectAsset("my_crop_18_37", 18, 37, false))
            add(CropAspectAsset("my_crop_16_10", 16, 10, false))
            add(CropAspectAsset("my_crop_10_16", 10, 16, false))
        }

        settingsList.getSettingsModel(UiConfigAspect::class.java).aspectList.apply {
            add(ToggleAspectItem(CropAspectItem("my_crop_2_1"), CropAspectItem("my_crop_1_2")))
            add(ToggleAspectItem(CropAspectItem("my_crop_19_9"), CropAspectItem("my_crop_9_19")))
            add(ToggleAspectItem(CropAspectItem("my_crop_5_4"), CropAspectItem("my_crop_4_5")))
            add(ToggleAspectItem(CropAspectItem("my_crop_37_18"), CropAspectItem("my_crop_18_37")))
            add(ToggleAspectItem(CropAspectItem("my_crop_16_10"), CropAspectItem("my_crop_10_16")))
        }

        settingsList.getSettingsModel(UiConfigFilter::class.java).setFilterList(
                FilterPackBasic.getFilterPack()
        )

        settingsList.getSettingsModel(UiConfigText::class.java).setFontList(
                FontPackBasic.getFontPack()
        )

        settingsList.getSettingsModel(BrushSettings::class.java).apply {
            brushColor = config.editorBrushColor
            brushHardness = config.editorBrushHardness
            brushSize = config.editorBrushSize
        }

        // do not use Text Design, it takes up too much space
        val tools = settingsList.getSettingsModel(UiConfigMainMenu::class.java).toolList
        val newTools = tools.filterNot {
            it.name!!.isEmpty()
        }.toMutableList() as ArrayList<ToolItem>

        // move Focus to the end, as it is the least used
        // on some devices it is not obvious that the toolbar can be scrolled horizontally, so move the best ones to the start to make them visible
        val focus = newTools.firstOrNull { it.name == getString(R.string.pesdk_focus_title_name) }
        if (focus != null) {
            newTools.remove(focus)
            newTools.add(focus)
        }

        settingsList.getSettingsModel(UiConfigMainMenu::class.java).setToolList(newTools)

        settingsList.getSettingsModel(UiConfigTheme::class.java).theme = R.style.Imgly_Theme_NoFullscreen

        settingsList.getSettingsModel(EditorSaveSettings::class.java)
                .setExportFormat(EditorSaveSettings.FORMAT.AUTO)
                .setOutputFilePath("$filesDir/editor/$filename")
                .savePolicy = EditorSaveSettings.SavePolicy.RETURN_SOURCE_OR_CREATE_OUTPUT_IF_NECESSARY

        return settingsList
    }
}
