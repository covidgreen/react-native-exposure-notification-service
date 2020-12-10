package ie.gov.tracing.storage;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import ie.gov.tracing.nearby.riskcalculation.WindowData;

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

  @ColumnInfo(name = "exposure_contact_date")
  private long exposureContactDate;

  @ColumnInfo(name = "window_data", defaultValue = "")
  @NonNull
  private String windowData = "";

  public ExposureEntity(int daysSinceLastExposure, int matchedKeyCount,
                 int maximumRiskScore, int summationRiskScore,
                 String attenuationDurations, long exposureContactDate) {
    this.createdTimestampMs = System.currentTimeMillis();

    this.daysSinceLastExposure = daysSinceLastExposure;
    this.matchedKeyCount = matchedKeyCount;
    this.maximumRiskScore = maximumRiskScore;
    this.summationRiskScore = summationRiskScore;
    this.attenuationDurations = attenuationDurations;
    this.exposureContactDate = exposureContactDate;

  }

  public static ExposureEntity create(int daysSinceLastExposure, int matchedKeyCount,
                                      int maximumRiskScore, int summationRiskScore,
                                      String attenuationDurations, long exposureContactDate) {
    return new ExposureEntity(daysSinceLastExposure, matchedKeyCount,
            maximumRiskScore, summationRiskScore, attenuationDurations, exposureContactDate);
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

  public long getExposureContactDate() {
    return exposureContactDate;
  }

  public String attenuationDurations() {
    return this.attenuationDurations;
  }

  public void setMatchedKeyCount(int matchedKeyCount) {
    this.matchedKeyCount = matchedKeyCount;
  }

  public void setWindows(List<WindowData> windows) {
    this.windowData = convertWindowsToJson(windows);
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

  private String convertWindowsToJson(List<WindowData> windows) {
    Gson gson = new Gson();
    return gson.toJson(windows);
  }

  public String windowData() {
    return windowData;
  }

  public void setWindowData(String windows) {
    this.windowData = windows;
  }

  public List<WindowData> getWindowData() {
    Gson gson = new Gson();

    Type windowType = new TypeToken<ArrayList<WindowData>>(){}.getType();

    if (this.windowData.isEmpty()) {
      return new ArrayList<WindowData>();
    } else {
      return gson.fromJson(this.windowData, windowType);
    }
  }

}