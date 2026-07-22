package wfos.bgrxassembly.configuration.rgrip

import csw.location.api.scaladsl.LocationService
import org.apache.pekko.actor.typed.ActorSystem
import wfos.bgrxassembly.configuration.ConfigFetcher

import java.nio.file.Path

object RGripLookupService {

  private var lookup: Option[RGripLookup] = None

  /**
   * Called once during Assembly startup.
   */
  def initialize(
      actorSystem: ActorSystem[?],
      locationService: LocationService,
      path: Path
  ): Unit =
    load(actorSystem, locationService, path)

  /**
   * Called while the Assembly is running to reload
   * the same or a different lookup table.
   */
  def reload(
      actorSystem: ActorSystem[?],
      locationService: LocationService,
      path: Path
  ): Unit =
    load(actorSystem, locationService, path)

  /**
   * Common loading logic.
   */
  private def load(
      actorSystem: ActorSystem[?],
      locationService: LocationService,
      path: Path
  ): Unit = {

    val inputStream =
      ConfigFetcher.fetch(
        actorSystem,
        locationService,
        path
      )

    lookup = Some(
      RGripLookupLoader.load(inputStream)
    )
  }

  def assemblyCoordinate(
      rotationAngle: Int
  ): Option[Int] =
    findEntry(rotationAngle).map(_.assemblyCoordinate)

  def hcdCoordinate(
      rotationAngle: Int
  ): Option[Int] =
    findEntry(rotationAngle).map(_.hcdCoordinate)

  private def findEntry(
      rotationAngle: Int
  ): Option[RGripLookupEntry] = {

    val lookupTable =
      lookup.getOrElse {
        throw new IllegalStateException(
          "RGripLookupService has not been initialized."
        )
      }

    lookupTable.entries.find(_.rotationAngle == rotationAngle)
  }

  def entries: Vector[RGripLookupEntry] =
    lookup.getOrElse {
      throw new IllegalStateException(
        "RGripLookupService has not been initialized."
      )
    }.entries
}
