package wfos.bgrxassembly.configuration.rgrip

import org.apache.poi.ss.usermodel.{CellType, Row}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.io.InputStream
import scala.jdk.CollectionConverters.*

object RGripLookupLoader {

  def load(inputStream: InputStream): RGripLookup = {

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

      RGripLookup(entries)

    }
    finally {
      workbook.close()
      inputStream.close()
    }
  }

  private def readNumericRow(row: Row): Vector[Int] = {
    row
      .iterator()
      .asScala
      .drop(1)
      .map { cell =>
        cell.getCellType match {
          case CellType.NUMERIC =>
            cell.getNumericCellValue.toInt

          case _ =>
            cell.toString
              .replace("°", "")
              .replace("mm", "")
              .trim
              .toInt
        }
      }
      .toVector
  }
}
