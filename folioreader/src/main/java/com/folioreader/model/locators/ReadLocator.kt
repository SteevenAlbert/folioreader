package com.folioreader.model.locators

import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.databind.ObjectMapper
import com.folioreader.util.ObjectMapperSingleton
import org.readium.r2.shared.Locations
import org.readium.r2.shared.Locator
import org.readium.r2.shared.LocatorText

@JsonPropertyOrder("bookId", "href", "created", "locations")
@JsonIgnoreProperties(ignoreUnknown = true)
open class ReadLocator : Locator, Parcelable {

    var bookId: String

    @Suppress("unused") // Required for fromJSON()
    constructor() : this("", "", 0, Locations())

    constructor(bookId: String, href: String, created: Long, locations: Locations) :
            this(bookId, href, created, "", locations, null)

    constructor(
        bookId: String, href: String, created: Long, title: String, locations: Locations,
        text: LocatorText?
    ) : super(href, created, title, locations, text) {
        this.bookId = bookId
    }

    constructor(parcel: Parcel) : this(
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readLong(),
        parcel.readString()!!,
        readLocationsFromParcel(parcel),
        readLocatorTextFromParcel(parcel)
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(bookId)
        dest.writeString(href)
        dest.writeLong(created)
        dest.writeString(title)
        writeLocationsToParcel(dest, locations)
        writeLocatorTextToParcel(dest, text)
    }

    private fun writeLocationsToParcel(dest: Parcel, locations: Locations) {
        // Write locations as JSON string since it might not be Parcelable
        try {
            val objectMapper = ObjectMapper()
            val locationsJson = objectMapper.writeValueAsString(locations)
            dest.writeString(locationsJson)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error writing locations to parcel", e)
            dest.writeString(null)
        }
    }

    private fun writeLocatorTextToParcel(dest: Parcel, text: LocatorText?) {
        // Write text as JSON string since it might not be Parcelable
        try {
            if (text != null) {
                val objectMapper = ObjectMapper()
                val textJson = objectMapper.writeValueAsString(text)
                dest.writeString(textJson)
            } else {
                dest.writeString(null)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error writing locator text to parcel", e)
            dest.writeString(null)
        }
    }

    companion object {

        @JvmField
        val LOG_TAG: String = ReadLocator::class.java.simpleName

        @JvmStatic
        fun fromJson(json: String?): ReadLocator? {
            return try {
                ObjectMapperSingleton.getObjectMapper()
                    .reader()
                    .forType(ReadLocator::class.java)
                    .readValue(json)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "-> ", e)
                null
            }
        }

        private fun readLocationsFromParcel(parcel: Parcel): Locations {
            return try {
                val locationsJson = parcel.readString()
                if (locationsJson != null) {
                    val objectMapper = ObjectMapper()
                    objectMapper.readValue(locationsJson, Locations::class.java)
                } else {
                    Locations()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error reading locations from parcel", e)
                Locations()
            }
        }

        private fun readLocatorTextFromParcel(parcel: Parcel): LocatorText? {
            return try {
                val textJson = parcel.readString()
                if (textJson != null) {
                    val objectMapper = ObjectMapper()
                    objectMapper.readValue(textJson, LocatorText::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error reading locator text from parcel", e)
                null
            }
        }

        @JvmField
        val CREATOR = object : Parcelable.Creator<ReadLocator> {
            override fun createFromParcel(parcel: Parcel): ReadLocator {
                return ReadLocator(parcel)
            }

            override fun newArray(size: Int): Array<ReadLocator?> {
                return arrayOfNulls(size)
            }
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    fun toJson(): String? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
            objectMapper.writeValueAsString(this)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "-> ", e)
            null
        }
    }
}
