package wfos.bgrxassembly.configuration

import cps.compat.FutureAsync.*
import csw.config.api.scaladsl.ConfigClientService
import csw.config.client.scaladsl.ConfigClientFactory
import csw.location.api.scaladsl.LocationService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.StreamConverters
import org.apache.poi.ss.usermodel.{CellType, Row}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.io.InputStream
import java.nio.file.Path
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object RGripLookupLoader {

  case class RGripLookupEntry(
      rotationAngle: Double,
      assemblyCoordinate: Double,
      hcdCoordinate: Double
  )

  case class RGripLookup(
      entries: Vector[RGripLookupEntry]
  )

  def load(
      actorSystem: ActorSystem[?],
      locationService: LocationService,
      path: Path
  ): RGripLookup = {

    println(s"[RGripLookupLoader] Fetching: $path")

    given ActorSystem[?]   = actorSystem
    given Materializer     = Materializer(actorSystem)
    given ExecutionContext = actorSystem.executionContext

    val clientApi: ConfigClientService =
      ConfigClientFactory.clientApi(actorSystem, locationService)

    val future = async {

      val maybeConfig =
        await(clientApi.getActive(path))

      maybeConfig match {

        case Some(configData) =>
          println(s" &&&&&&&&&&&&&&&&&&& [RGripLookupLoader] File found (${configData.length} bytes)")

          val inputStream =
            configData.source.runWith(StreamConverters.asInputStream())

          parse(inputStream)

        case None =>
          throw new RuntimeException(s"Configuration file not found: $path")
      }
    }

    Await.result(future, 30.seconds)
  }

  private def parse(inputStream: InputStream): RGripLookup = {

    val workbook = new XSSFWorkbook(inputStream)

    try {

      val sheet = workbook.getSheetAt(0)

      val rows = sheet.iterator().asScala.toVector

      require(rows.size >= 3, "Invalid lookup table")

      val rotationAngles      = readNumericRow(rows(0))
      val assemblyCoordinates = readNumericRow(rows(1))
      val hcdCoordinates      = readNumericRow(rows(2))

      val entries =
        rotationAngles.indices.map { i =>
          RGripLookupEntry(
            rotationAngle = rotationAngles(i),
            assemblyCoordinate = assemblyCoordinates(i),
            hcdCoordinate = hcdCoordinates(i)
          )
        }.toVector

      println(s"[RGripLookupLoader] Parsed ${entries.size} lookup entries")

      RGripLookup(entries)

    }
    finally {
      workbook.close()
      inputStream.close()
    }
  }

  private def readNumericRow(row: Row): Vector[Double] = {
    row
      .iterator()
      .asScala
      .drop(1)
      .map { cell =>
        cell.getCellType match {
          case CellType.NUMERIC =>
            cell.getNumericCellValue

          case _ =>
            cell.toString
              .replace("°", "")
              .replace("mm", "")
              .trim
              .toDouble
        }
      }
      .toVector
  }
}
