package com.example.mobilityassist

import android.location.Location
import com.mappls.sdk.geojson.Point
import com.mappls.sdk.services.api.OnResponseCallback
import com.mappls.sdk.services.api.PlaceResponse
import com.mappls.sdk.services.api.directions.DirectionsCriteria
import com.mappls.sdk.services.api.directions.MapplsDirectionManager
import com.mappls.sdk.services.api.directions.MapplsDirections
import com.mappls.sdk.services.api.directions.models.DirectionsResponse
import com.mappls.sdk.services.api.directions.models.DirectionsRoute
import com.mappls.sdk.services.api.autosuggest.MapplsAutoSuggest
import com.mappls.sdk.services.api.autosuggest.MapplsAutosuggestManager
import com.mappls.sdk.services.api.autosuggest.model.AutoSuggestAtlasResponse
import com.mappls.sdk.services.api.geocoding.GeoCodeResponse
import com.mappls.sdk.services.api.geocoding.MapplsGeoCoding
import com.mappls.sdk.services.api.geocoding.MapplsGeoCodingManager
import com.mappls.sdk.services.api.reversegeocode.MapplsReverseGeoCode
import com.mappls.sdk.services.api.reversegeocode.MapplsReverseGeoCodeManager
import com.mappls.sdk.services.api.placedetail.MapplsPlaceDetail
import com.mappls.sdk.services.api.placedetail.MapplsPlaceDetailManager
import com.mappls.sdk.services.api.placedetail.model.PlaceDetailResponse

data class WalkingRoute(
    val destinationName: String,
    val destination: Point,
    val route: DirectionsRoute
)

data class PlaceCandidate(
    val pin: String?, val name: String, val address: String,
    val latitude: Double?, val longitude: Double?
) {
    val label: String get() = if (address.isBlank()) name else "$name — $address"
    fun point(): Point? = if (latitude != null && longitude != null &&
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 &&
        longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0))
        Point.fromLngLat(longitude, latitude) else null
}

enum class MapFailureKind { NO_RESULTS, NO_ROUTE, NETWORK, ACCESS, QUOTA, SERVICE, OTHER }
class MapFailure(val kind: MapFailureKind, val stage: String, val code: Int = 0) : Exception("$stage: $kind ($code)") {
    companion object {
        fun from(stage: String, code: Int, message: String): MapFailure {
            val lower = message.lowercase()
            val kind = when {
                code == 4 || "not provisioned" in lower -> MapFailureKind.SERVICE
                code == 403 || "quota" in lower || "limit" in lower -> MapFailureKind.QUOTA
                code == 401 || code in 1..6 || "auth" in lower || "invalid config" in lower -> MapFailureKind.ACCESS
                code == 204 || "no result" in lower ->
                    if (stage == "route") MapFailureKind.NO_ROUTE else MapFailureKind.NO_RESULTS
                code == 408 || "timeout" in lower || "network" in lower || "connect" in lower -> MapFailureKind.NETWORK
                else -> MapFailureKind.OTHER
            }
            return MapFailure(kind, stage, code)
        }
    }
}

/** Mappls search and pedestrian routing, with no driving or traffic route fallback. */
class MapplsWalkingRoutes {
    fun suggest(text: String, near: Location?, done: (Result<List<PlaceCandidate>>) -> Unit) {
        try {
            val builder = MapplsAutoSuggest.builder().query(text)
            if (near != null && PreciseLocationTracker.usable(near, 200f))
                builder.setLocation(near.latitude, near.longitude)
            MapplsAutosuggestManager.newInstance(builder.build())
                .call(object : OnResponseCallback<AutoSuggestAtlasResponse> {
                    override fun onSuccess(response: AutoSuggestAtlasResponse) {
                        val choices = (response.suggestedLocations.orEmpty() +
                            response.userAddedLocations.orEmpty()).distinctBy { it.mapplsPin }.take(8).mapNotNull { place ->
                            val name = place.placeName?.trim().orEmpty()
                            if (name.isBlank()) null else PlaceCandidate(
                                place.mapplsPin, name, place.placeAddress.orEmpty(),
                                place.latitude, place.longitude)
                        }
                        done(Result.success(choices))
                    }
                    override fun onError(code: Int, message: String) {
                        done(Result.failure(MapFailure.from("suggest", code, message)))
                    }
                })
        } catch (error: Exception) { done(Result.failure(error)) }
    }

    fun resolve(candidate: PlaceCandidate, done: (Result<Point>) -> Unit) {
        candidate.point()?.let { done(Result.success(it)); return }
        val pin = candidate.pin
        if (pin.isNullOrBlank()) {
            done(Result.failure(MapFailure(MapFailureKind.NO_RESULTS, "place")))
            return
        }
        try {
            val request = MapplsPlaceDetail.builder().mapplsPin(pin).build()
            MapplsPlaceDetailManager.newInstance(request)
                .call(object : OnResponseCallback<PlaceDetailResponse> {
                    override fun onSuccess(response: PlaceDetailResponse) {
                        val point = candidate.copy(latitude = response.latitude,
                            longitude = response.longitude).point()
                        if (point != null) done(Result.success(point))
                        else done(Result.failure(MapFailure(MapFailureKind.NO_RESULTS, "place")))
                    }
                    override fun onError(code: Int, message: String) {
                        done(Result.failure(MapFailure.from("place", code, message)))
                    }
                })
        } catch (error: Exception) { done(Result.failure(error)) }
    }

    fun describeOrigin(location: Location, done: (String?) -> Unit) {
        try {
            val request = MapplsReverseGeoCode.builder()
                .setLocation(location.latitude, location.longitude).build()
            MapplsReverseGeoCodeManager.newInstance(request).call(object : OnResponseCallback<PlaceResponse> {
                override fun onSuccess(response: PlaceResponse) {
                    done(response.places?.firstOrNull()?.formattedAddress)
                }
                override fun onError(code: Int, message: String) { done(null) }
            })
        } catch (_: Exception) { done(null) }
    }

    fun find(destinationText: String, origin: Location, done: (Result<WalkingRoute>) -> Unit) {
        try {
            val query = MapplsGeoCoding.builder().setAddress(destinationText).itemCount(1).build()
            MapplsGeoCodingManager.newInstance(query).call(object : OnResponseCallback<GeoCodeResponse> {
                override fun onSuccess(response: GeoCodeResponse) {
                    val place = response.results?.firstOrNull()
                    if (place == null || !place.latitude.isFinite() || !place.longitude.isFinite() ||
                        place.latitude !in -90.0..90.0 || place.longitude !in -180.0..180.0 ||
                        (place.latitude == 0.0 && place.longitude == 0.0)) {
                        done(Result.failure(MapFailure(MapFailureKind.NO_RESULTS, "search")))
                        return
                    }
                    val point = Point.fromLngLat(place.longitude, place.latitude)
                    val name = place.formattedAddress?.takeIf { it.isNotBlank() } ?: destinationText
                    routeFrom(origin, point, name, done)
                }

                override fun onError(code: Int, message: String) {
                    done(Result.failure(MapFailure.from("search", code, message)))
                }
            })
        } catch (error: Exception) {
            done(Result.failure(error))
        }
    }

    fun routeFrom(
        origin: Location,
        destination: Point,
        name: String,
        done: (Result<WalkingRoute>) -> Unit
    ) {
        try {
            val request = MapplsDirections.builder()
                .origin(Point.fromLngLat(origin.longitude, origin.latitude))
                .destination(destination)
                .profile(DirectionsCriteria.PROFILE_WALKING)
                .resource(DirectionsCriteria.RESOURCE_ROUTE)
                .overview(DirectionsCriteria.OVERVIEW_FULL)
                .steps(true)
                .build()
            MapplsDirectionManager.newInstance(request).call(object : OnResponseCallback<DirectionsResponse> {
                override fun onSuccess(response: DirectionsResponse) {
                    val route = response.routes()?.firstOrNull()
                    val distance = route?.distance()
                    val duration = route?.duration()
                    if (route == null || (route.getCoordinates()?.size ?: 0) < 2 ||
                        distance == null || !distance.isFinite() || distance <= 0.0 ||
                        duration == null || !duration.isFinite() || duration <= 0.0)
                        done(Result.failure(MapFailure(MapFailureKind.NO_ROUTE, "route")))
                    else done(Result.success(WalkingRoute(name, destination, route)))
                }

                override fun onError(code: Int, message: String) {
                    done(Result.failure(MapFailure.from("route", code, message)))
                }
            })
        } catch (error: Exception) {
            done(Result.failure(error))
        }
    }
}
