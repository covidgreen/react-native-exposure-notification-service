package ie.gov.tracing.storage;

import androidx.room.TypeConverter;

import org.threeten.bp.ZonedDateTime;
import org.threeten.bp.format.DateTimeFormatter;

/**
 * TypeConverters for converting to and from {@link ZonedDateTime} instances.
 */
public class ZonedDateTimeTypeConverter {

  private ZonedDateTimeTypeConverter() {
    // no instantiation
  }

  private static final DateTimeFormatter sFormatter = DateTimeFormatter.ISO_ZONED_DATE_TIME;

  @TypeConverter
  public static ZonedDateTime toOffsetDateTime(String timestamp) {
    if (timestamp != null) {
      return sFormatter.parse(timestamp, ZonedDateTime.FROM);
    } else {
      return null;
    }
  }

  @TypeConverter
  public static String fromOffsetDateTime(ZonedDateTime timestamp) {
    if (timestamp != null) {
      return timestamp.format(sFormatter);
    } else {
      return null;
    }
  }
}
