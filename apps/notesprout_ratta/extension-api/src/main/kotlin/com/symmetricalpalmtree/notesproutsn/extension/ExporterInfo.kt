package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What one exporter offers — returned by `INotebookExporter.describe()`. The format identity
 * (label · file extension · MIME) plus a bounded [options] list the host renders with its own
 * widgets. The constructor `require`s are the validation — unmarshal is validation (the family
 * rule), and a descriptor that fails them drops that exporter with a log line, never a crash.
 *
 * Wire form: `String formatLabel · String fileExtension · String mimeType ·
 * typed OptionDescriptor[] · int sourceKind · int bundleVersion` — the last two are compatible
 * tails. `sourceKind` is arc 18's: an old-shape descriptor ends after the option list and reads
 * as [ExporterContract.SOURCE_SOIL]. `bundleVersion` is arc 28's (D7): the highest
 * [PageBundle] version a [ExporterContract.SOURCE_PAGES] exporter reads, absent = 1, so a host
 * facing an exporter that never heard of endnotes writes the version-1 bundle it always did. An
 * old reader stops before whichever tail it does not know; readers of this version stop after
 * `bundleVersion`. Neither tail moved `API_VERSION`.
 */
class ExporterInfo(
    val formatLabel: String,
    val fileExtension: String,
    val mimeType: String,
    val options: List<OptionDescriptor>,
    val sourceKind: Int = ExporterContract.SOURCE_SOIL,
    val bundleVersion: Int = PageBundle.VERSION_1,
) : Parcelable {

    init {
        require(bundleVersion >= PageBundle.VERSION_1) { "bundle version $bundleVersion < 1" }
        require(
            sourceKind == ExporterContract.SOURCE_SOIL ||
                sourceKind == ExporterContract.SOURCE_PAGES ||
                sourceKind == ExporterContract.SOURCE_DOCUMENT,
        ) { "unknown source kind $sourceKind" }
        OptionDescriptor.requireLabel(formatLabel, "format label")
        require(
            fileExtension.isNotEmpty() &&
                fileExtension.length <= ExporterContract.MAX_FILE_EXTENSION_CHARS &&
                fileExtension.all { it in 'a'..'z' || it in '0'..'9' },
        ) { "file extension '$fileExtension' is not [a-z0-9]{1..${ExporterContract.MAX_FILE_EXTENSION_CHARS}}" }
        require(
            mimeType.length in 3..ExporterContract.MAX_MIME_CHARS &&
                mimeType.count { it == '/' } == 1 &&
                !mimeType.startsWith('/') && !mimeType.endsWith('/'),
        ) { "malformed MIME type '$mimeType'" }
        require(options.size <= ExporterContract.MAX_OPTIONS) {
            "${options.size} options > ${ExporterContract.MAX_OPTIONS}"
        }
        require(options.map { it.id }.toSet().size == options.size) { "duplicate option ids" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(formatLabel)
        dest.writeString(fileExtension)
        dest.writeString(mimeType)
        dest.writeTypedList(options)
        dest.writeInt(sourceKind)
        dest.writeInt(bundleVersion)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ExporterInfo {
            val formatLabel = parcel.readString() ?: ""
            val fileExtension = parcel.readString() ?: ""
            val mimeType = parcel.readString() ?: ""
            val options = parcel.createTypedArrayList(OptionDescriptor.CREATOR) ?: arrayListOf()
            // The compatible tail: the descriptor is the reply's whole payload, so an old-shape
            // parcel simply runs out here and the absent tail means SOURCE_SOIL.
            val sourceKind =
                if (parcel.dataAvail() > 0) parcel.readInt() else ExporterContract.SOURCE_SOIL
            val bundleVersion =
                if (parcel.dataAvail() > 0) parcel.readInt() else PageBundle.VERSION_1
            return ExporterInfo(formatLabel, fileExtension, mimeType, options, sourceKind, bundleVersion)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ExporterInfo> = object : Parcelable.Creator<ExporterInfo> {
            override fun createFromParcel(parcel: Parcel): ExporterInfo = read(parcel)
            override fun newArray(size: Int): Array<ExporterInfo?> = arrayOfNulls(size)
        }
    }
}
