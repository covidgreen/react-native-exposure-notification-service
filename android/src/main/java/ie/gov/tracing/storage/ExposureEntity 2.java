package ie.gov.tracing.storage;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class ExposureEntity {

  @PrimaryKey(autoGenerate = true)
  long id;

  @ColumnInfo(name = "created_timestamp_ms")
  private long createdTimestampMs;

  @ColumnInfo(name = "days_since_last_exposure")
  private int daysSinceLastExposure;

  @ColumnInfo(name = "matched_key_count")
  private int matchedKeyCount;

  @ColumnInfo(name = "maximum_risk_score")
  private int maximumRiskScore;

  @ColumnInfo(name = "summation_risk_score")
  private int summationRiskScore;

  @ColumnInfo(name = "attenuation_durations")
  private String attenuationDurations;

  ExposureEntity(int daysSinceLastExposure, int matchedKeyCount,
                 int maximumRiskScore, int summationRiskScore,
                 String attenuationDurations) {
    this.createdTimestampMs = System.currentTimeMillis();

    this.daysSinceLastExposure = daysSinceLastExposure;
    this.matchedKeyCount = matchedKeyCount;
    this.maximumRiskScore = maximumRiskScore;
    this.summationRiskScore = summationRiskScore;
    this.attenuationDurations = attenuationDurations;
  }

  public static ExposureEntity create(int daysSinceLastExposure, int matchedKeyCount,
                                      int maximumRiskScore, int summationRiskScore,
                                      String attenuationDurations) {
    return new ExposureEntity(daysSinceLastExposure, matchedKeyCount,
            maximumRiskScore, summationRiskScore, attenuationDurations);
  }

  public long getId() {
    return id;
  }

  public long getCreatedTimestampMs() {
    return createdTimestampMs;
  }

  void setCreatedTimestampMs(long ms) {
    this.createdTimestampMs = ms;
  }

  public int daysSinceLastExposure() {
    return daysSinceLastExposure;
  }

  public int matchedKeyCount() {
    return matchedKeyCount;
  }

  public int maximumRiskScore() {
    return maximumRiskScore;
  }

  public int summationRiskScore() {
    return summationRiskScore;
  }

  public String attenuationDurations() {
    return this.attenuationDurations;
  }

  public void setMatchedKeyCount(int matchedKeyCount) {
    this.matchedKeyCount = matchedKeyCount;
  }

  public void setDaysSinceLastExposure(int daysSinceLastExposure) {
    this.daysSinceLastExposure = daysSinceLastExposure;
  }

  public void setMaximumRiskScore(int maximumRiskScore) {
    this.maximumRiskScore = maximumRiskScore;
  }

  public void setSummationRiskScore(int summationRiskScore) {
    this.summationRiskScore = summationRiskScore;
  }

  public void setAttenuationDurations(String attenuationDurations) {
    this.attenuationDurations = attenuationDurations;
  }
}