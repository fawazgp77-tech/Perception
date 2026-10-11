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
import com.mappls.sdk.services.api.geocoding.GeoCodeResponse
import com.mappls.sdk.services.api.geocoding.MapplsGeoCoding
import com.mappls.sdk.services.api.geocoding.MapplsGeoCodingManager
import com.mappls.sdk.services.api.reversegeocode.MapplsReverseGeoCode
import com.mappls.sdk.services.api.reversegeocode.MapplsReverseGeoCodeManager

data class WalkingRoute(
    val destinationName: String,
    val destination: Point,
    val route: DirectionsRoute
)

/** Mappls search and pedestrian routing, with no driving or traffic route fallback. */
class MapplsWalkingRoutes {
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
                        done(Result.failure(IllegalStateException("Destination not found")))
                        return
                    }
                    val point = Point.fromLngLat(place.longitude, place.latitude)
                    val name = place.formattedAddress?.takeIf { it.isNotBlank() } ?: destinationText
                    routeFrom(origin, point, name, done)
                }

                override fun onError(code: Int, message: String) {
                    done(Result.failure(IllegalStateException("Mappls search error $code: $message")))
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
                        done(Result.failure(IllegalStateException("No walking route")))
                    else done(Result.success(WalkingRoute(name, destination, route)))
                }

                override fun onError(code: Int, message: String) {
                    done(Result.failure(IllegalStateException("Mappls walking route error $code: $message")))
                }
            })
        } catch (error: Exception) {
            done(Result.failure(error))
        }
    }
}
