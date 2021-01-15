package com.exasol.cloudetl.orc

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hive.ql.exec.vector._
import org.apache.orc.OrcFile
import org.apache.orc.TypeDescription
import org.apache.orc.TypeDescription.Category

/**
 * A helper class that writes Orc types into a file.
 */
@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
class OrcTestDataWriter(path: Path, conf: Configuration) {

  final def write[T <: AnyRef](
    schema: TypeDescription,
    values: List[T]
  ): Unit = {
    val writer = OrcFile.createWriter(path, OrcFile.writerOptions(conf).setSchema(schema))
    val schemaChildren = schema.getChildren()
    val batch = schema.createRowBatch()
    val columnWriters = Array.ofDim[(Any, Int) => Unit](schemaChildren.size())
    for { i <- 0 until schemaChildren.size() } {
      columnWriters(i) = getColumnSetter(schemaChildren.get(i), batch.cols(i))
    }
    batch.size = 0
    values.foreach {
      case value =>
        columnWriters.foreach(writer => writer(value, batch.size))
        batch.size += 1
    }
    writer.addRowBatch(batch)
    writer.close()
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.Recursion"))
  private[this] def getColumnSetter(
    orcType: TypeDescription,
    column: ColumnVector
  ): (Any, Int) => Unit =
    orcType.getCategory() match {
      case Category.INT    => longWriter(column.asInstanceOf[LongColumnVector])
      case Category.LONG   => longWriter(column.asInstanceOf[LongColumnVector])
      case Category.DOUBLE => doubleWriter(column.asInstanceOf[DoubleColumnVector])
      case Category.STRING => stringWriter(column.asInstanceOf[BytesColumnVector])
      case Category.LIST   => listWriter(column.asInstanceOf[ListColumnVector], orcType)
      case Category.MAP    => mapWriter(column.asInstanceOf[MapColumnVector], orcType)
      case Category.STRUCT => structWriter(column.asInstanceOf[StructColumnVector], orcType)
      case _ =>
        throw new UnsupportedOperationException(s"Unknown writer type '$orcType'")
    }

  private[this] def longWriter(column: LongColumnVector): (Any, Int) => Unit =
    (value: Any, index: Int) =>
      value match {
        case x: Int   => column.vector(index) = x.toLong
        case x: Long  => column.vector(index) = x
        case x: Short => column.vector(index) = x.toLong
        case x: Byte  => column.vector(index) = x.toLong
        case _ =>
          column.noNulls = false
          column.isNull(index) = true
    }

  private[this] def doubleWriter(column: DoubleColumnVector): (Any, Int) => Unit =
    (value: Any, index: Int) =>
      value match {
        case d: Double => column.vector(index) = d
        case f: Float  => column.vector(index) = f.toDouble
        case _         => setNull(column, index)
    }

  private[this] def stringWriter(column: BytesColumnVector): (Any, Int) => Unit =
    (value: Any, index: Int) =>
      value match {
        case str: String => column.setVal(index, str.getBytes("UTF-8"))
        case _           => setNull(column, index)
    }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  private[this] def listWriter(
    column: ListColumnVector,
    orcType: TypeDescription
  ): (Any, Int) => Unit = {
    val innerSetter = getColumnSetter(orcType.getChildren().get(0), column.child)
    (value: Any, index: Int) =>
      value match {
        case seq: Iterable[_] if seq.nonEmpty =>
          val len = seq.size
          column.offsets(index) = column.childCount.toLong
          column.lengths(index) = len.toLong
          column.childCount += len
          column.child.ensureSize(column.childCount, column.offsets(index) != 0)
          var offset = 0
          seq.foreach { v =>
            innerSetter(v, column.offsets(index).toInt + offset)
            offset += 1
          }
        case _ => setNull(column, index)
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  private[this] def mapWriter(
    column: MapColumnVector,
    orcType: TypeDescription
  ): (Any, Int) => Unit = {
    val keySetter = getColumnSetter(orcType.getChildren.get(0), column.keys)
    val valueSetter = getColumnSetter(orcType.getChildren.get(1), column.values)
    (value: Any, index: Int) =>
      value match {
        case map: Map[_, _] if map != null && map.nonEmpty =>
          val len = map.size
          column.offsets(index) = column.childCount.toLong
          column.lengths(index) = len.toLong
          column.childCount += len
          column.keys.ensureSize(column.childCount, column.offsets(index) != 0)
          column.values.ensureSize(column.childCount, column.offsets(index) != 0)
          var offset = 0
          map.foreach {
            case (key, value) =>
              keySetter(key, column.offsets(index).toInt + offset)
              valueSetter(value, column.offsets(index).toInt + offset)
              offset += 1
          }
        case _ => setNull(column, index)
      }
  }

  private[this] def structWriter(
    column: StructColumnVector,
    orcType: TypeDescription
  ): (Any, Int) => Unit = {
    val columns = orcType.getChildren()
    val fieldNames = orcType.getFieldNames()
    val fieldSetters = (0 until columns.size()).map {
      case idx => fieldNames.get(idx) -> getColumnSetter(columns.get(idx), column.fields(idx))
    }.toMap
    (value: Any, index: Int) =>
      value match {
        case m: Map[_, _] =>
          val map = m.asInstanceOf[Map[String, Any]]
          fieldSetters.foreach {
            case (key, innerSetter) =>
              val mapValue = map.getOrElse(key, null)
              innerSetter(mapValue, index)
          }
        case _ => setNull(column, index)
      }
  }

  private[this] def setNull(column: ColumnVector, index: Int): Unit = {
    if (column.isInstanceOf[ListColumnVector]) {
      column.asInstanceOf[ListColumnVector].lengths(index) = 0
    }
    if (column.isInstanceOf[MapColumnVector]) {
      column.asInstanceOf[MapColumnVector].lengths(index) = 0
    }
    column.noNulls = false
    column.isNull(index) = true
  }

}