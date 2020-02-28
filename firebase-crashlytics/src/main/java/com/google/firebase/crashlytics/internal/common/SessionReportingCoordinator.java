// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.crashlytics.internal.common;

import com.google.android.gms.tasks.Task;
import com.google.firebase.crashlytics.core.UserMetadata;
import com.google.firebase.crashlytics.internal.Logger;
import com.google.firebase.crashlytics.internal.log.LogFileManager;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport.CustomAttribute;
import com.google.firebase.crashlytics.internal.model.ImmutableList;
import com.google.firebase.crashlytics.internal.persistence.CrashlyticsReportPersistence;
import com.google.firebase.crashlytics.internal.send.DataTransportCrashlyticsReportSender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * This class handles Crashlytics lifecycle events and coordinates session data capture and
 * persistence, as well as sending of reports to Firebase Crashlytics.
 */
public class SessionReportingCoordinator implements CrashlyticsLifecycleEvents {

  public interface SendReportPredicate {
    boolean shouldSendViaDataTransport();
  }

  private static final String EVENT_TYPE_CRASH = "crash";
  private static final String EVENT_TYPE_LOGGED = "error";
  private static final int EVENT_THREAD_IMPORTANCE = 4;
  private static final int MAX_CHAINED_EXCEPTION_DEPTH = 8;

  private final CrashlyticsReportDataCapture dataCapture;
  private final CrashlyticsReportPersistence reportPersistence;
  private final DataTransportCrashlyticsReportSender reportsSender;
  private final LogFileManager logFileManager;
  private final UserMetadata reportMetadata;
  private final CurrentTimeProvider currentTimeProvider;

  private String currentSessionId;

  SessionReportingCoordinator(
      CrashlyticsReportDataCapture dataCapture,
      CrashlyticsReportPersistence reportPersistence,
      DataTransportCrashlyticsReportSender reportsSender,
      LogFileManager logFileManager,
      UserMetadata reportMetadata,
      CurrentTimeProvider currentTimeProvider) {
    this.dataCapture = dataCapture;
    this.reportPersistence = reportPersistence;
    this.reportsSender = reportsSender;
    this.logFileManager = logFileManager;
    this.reportMetadata = reportMetadata;
    this.currentTimeProvider = currentTimeProvider;
  }

  @Override
  public void onBeginSession(String sessionId) {
    final long timestamp = currentTimeProvider.getCurrentTimeMillis() / 1000;
    currentSessionId = sessionId;

    final CrashlyticsReport capturedReport = dataCapture.captureReportData(sessionId, timestamp);

    reportPersistence.persistReport(capturedReport);
  }

  @Override
  public void onLog(long timestamp, String log) {
    logFileManager.writeToLog(timestamp, log);
  }

  @Override
  public void onCustomKey(String key, String value) {
    reportMetadata.setCustomKey(key, value);
  }

  @Override
  public void onUserId(String userId) {
    reportMetadata.setUserId(userId);
  }

  @Override
  public void onEndSession() {
    currentSessionId = null;
  }

  public void persistFatalEvent(Throwable event, Thread thread) {
    persistEvent(event, thread, EVENT_TYPE_CRASH, true);
  }

  public void persistNonFatalEvent(Throwable event, Thread thread) {
    persistEvent(event, thread, EVENT_TYPE_LOGGED, false);
  }

  public void persistUserId() {
    reportPersistence.persistUserIdForSession(reportMetadata.getUserId(), currentSessionId);
  }

  /** Creates finalized reports for all sessions besides the current session. */
  public void finalizeSessions() {
    reportPersistence.finalizeReports(currentSessionId);
  }

  /**
   * Send all finalized reports.
   *
   * @param organizationId The organization ID this crash report should be associated with
   * @param reportSendCompleteExecutor executor on which to run report cleanup after each report is
   *     sent.
   * @param sendReportPredicate Predicate determining whether to send reports before cleaning them
   *     up
   */
  public void sendReports(
      String organizationId,
      Executor reportSendCompleteExecutor,
      SendReportPredicate sendReportPredicate) {
    if (!sendReportPredicate.shouldSendViaDataTransport()) {
      Logger.getLogger().d(Logger.TAG, "Send via DataTransport disabled. Removing reports.");
      reportPersistence.deleteAllReports();
      return;
    }
    final List<CrashlyticsReport> reportsToSend = reportPersistence.loadFinalizedReports();
    for (CrashlyticsReport report : reportsToSend) {
      reportsSender
          .sendReport(report.withOrganizationId(organizationId))
          .continueWith(reportSendCompleteExecutor, this::onReportSendComplete);
    }
  }

  private void persistEvent(
      Throwable event, Thread thread, String eventType, boolean includeAllThreads) {
    final long timestamp = currentTimeProvider.getCurrentTimeMillis() / 1000;

    final boolean isHighPriority = eventType.equals(EVENT_TYPE_CRASH);

    final CrashlyticsReport.Session.Event capturedEvent =
        dataCapture.captureEventData(
            event,
            thread,
            eventType,
            timestamp,
            EVENT_THREAD_IMPORTANCE,
            MAX_CHAINED_EXCEPTION_DEPTH,
            includeAllThreads);

    final CrashlyticsReport.Session.Event.Builder eventBuilder = capturedEvent.toBuilder();

    final String content = logFileManager.getLogString();

    if (content != null) {
      eventBuilder.setLog(
          CrashlyticsReport.Session.Event.Log.builder().setContent(content).build());
    } else {
      Logger.getLogger().d(Logger.TAG, "No log data to include with this event.");
    }

    logFileManager.clearLog(); // Clear log to prepare for next event.

    final List<CustomAttribute> sortedCustomAttributes =
        getSortedCustomAttributes(reportMetadata.getCustomKeys());

    if (sortedCustomAttributes != null) {
      eventBuilder.setApp(
          capturedEvent
              .getApp()
              .toBuilder()
              .setCustomAttributes(ImmutableList.from(sortedCustomAttributes))
              .build());
    }

    reportPersistence.persistEvent(eventBuilder.build(), currentSessionId, isHighPriority);
  }

  private boolean onReportSendComplete(Task<CrashlyticsReport> task) {
    if (task.isSuccessful()) {
      // TODO: if the report is fatal, send an analytics event.
      final CrashlyticsReport report = task.getResult();
      final String reportId = report.getSession().getIdentifier();
      Logger.getLogger().i(Logger.TAG, "Crashlytics report sent successfully: " + reportId);
      reportPersistence.deleteFinalizedReport(reportId);
      return true;
    }
    // TODO: Something went wrong. Log? Throw?
    return false;
  }

  private static List<CustomAttribute> getSortedCustomAttributes(Map<String, String> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return null;
    }

    ArrayList<CustomAttribute> attributesList = new ArrayList<>();
    attributesList.ensureCapacity(attributes.size());
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      attributesList.add(
          CustomAttribute.builder().setKey(entry.getKey()).setValue(entry.getValue()).build());
    }

    // Sort by key
    Collections.sort(
        attributesList,
        (CustomAttribute attr1, CustomAttribute attr2) -> attr1.getKey().compareTo(attr2.getKey()));

    return attributesList;
  }
}