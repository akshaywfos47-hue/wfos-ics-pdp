package config_common.rgrip_parser

import config_common.ConfigFetcher
import csw.location.api.scaladsl.LocationService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.poi.ss.usermodel.{CellType, Row}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.io.InputStream
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

class RGripLookupParser {

  /**
   * Loads the RGRIP lookup table from Configuration Service,
   * parses it and returns the Scala model.
   */
  def load(
            actorSystem: ActorSystem[?],
            locationService: LocationService,
            path: Path
          ): RGripLookup = {

    val inputStream: InputStream =
      ConfigFetcher.fetch(
        path,
        actorSystem,
        locationService
      )

    parse(inputStream)
  }

  /**
   * Parses the Excel lookup table.
   */
  private def parse(inputStream: InputStream): RGripLookup = {

    val workbook = new XSSFWorkbook(inputStream)

    try {

      val sheet = workbook.getSheetAt(0)

      val rows = sheet.iterator().asScala.toVector

      require(
        rows.size >= 3,
        "RGRIP lookup table must contain at least three rows."
      )

      val rotationAngles =
        readNumericRow(rows(0))

      val assemblyCoordinates =
        readNumericRow(rows(1))

      val hcdCoordinates =
        readNumericRow(rows(2))

      RGripLookup(
        rotationAngles,
        assemblyCoordinates,
        hcdCoordinates
      )

    }
    finally {
      workbook.close()
      inputStream.close()
    }
  }

  /**
   * Reads one numeric row from the Excel sheet.
   * The first cell is treated as a label and skipped.
   */
  private def readNumericRow(
                              row: Row
                            ): Vector[Double] = {

    row.iterator().asScala
      .drop(1)
      .map { cell =>
        cell.getCellType match {
          case CellType.NUMERIC => cell.getNumericCellValue
          case _                => cell.toString.toDouble
        }
      }
      .toVector
  }

}