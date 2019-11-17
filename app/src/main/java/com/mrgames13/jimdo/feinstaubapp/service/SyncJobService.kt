/*
 * Copyright © 2019 Marc Auberer. All rights reserved.
 */

package com.mrgames13.jimdo.feinstaubapp.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.mrgames13.jimdo.feinstaubapp.R
import com.mrgames13.jimdo.feinstaubapp.model.DataRecord
import com.mrgames13.jimdo.feinstaubapp.model.Sensor
import com.mrgames13.jimdo.feinstaubapp.network.ServerMessagingUtils
import com.mrgames13.jimdo.feinstaubapp.network.loadDataRecords
import com.mrgames13.jimdo.feinstaubapp.tool.Constants
import com.mrgames13.jimdo.feinstaubapp.tool.NotificationUtils
import com.mrgames13.jimdo.feinstaubapp.tool.StorageUtils
import com.mrgames13.jimdo.feinstaubapp.tool.Tools
import com.mrgames13.jimdo.feinstaubapp.widget.WidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class SyncJobService : JobService() {

    // Variables as objects
    private var calendar: Calendar? = null
    private var records: ArrayList<DataRecord>? = null

    // Utils packages
    private lateinit var su: StorageUtils
    private lateinit var smu: ServerMessagingUtils
    private lateinit var nu: NotificationUtils

    // Variables
    private var limitP1: Int = 0
    private var limitP2: Int = 0
    private var limitTemp: Int = 0
    private var limitHumidity: Int = 0
    private var limitPressure: Int = 0
    private var selectedDayTimestamp: Long = 0

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d("FA", "Job started from foreground")
        doWork(true, null)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d("FA", "Job started from background")
        doWork(false, params)
        return true
    }

    override fun onStopJob(jobParameters: JobParameters): Boolean {
        Log.w("FA", "Job stopped before completion")
        return false
    }

    private fun doWork(fromForeground: Boolean, params: JobParameters?) {
        // Initialize StorageUtils
        su = StorageUtils(this)

        // Initialize ServerMessagingUtils
        smu = ServerMessagingUtils(this, su)

        // Initialize NotificationUtils
        nu = NotificationUtils(this)

        // Initialize calendar
        if (selectedDayTimestamp == 0L || calendar == null) {
            calendar = Calendar.getInstance()
            calendar!!.set(Calendar.HOUR_OF_DAY, 0)
            calendar!!.set(Calendar.MINUTE, 0)
            calendar!!.set(Calendar.SECOND, 0)
            calendar!!.set(Calendar.MILLISECOND, 0)
            selectedDayTimestamp = calendar!!.time.time
        }

        // Check if internet is available
        if (smu.isInternetAvailable) {
            // Get max limit from SharedPreferences
            try { // This try-catch-block is temporary placed because of an error occurrence of a NumberFormatException
                limitP1 = Integer.parseInt(su.getString("limitP1", Constants.DEFAULT_P1_LIMIT.toString()))
                limitP2 = Integer.parseInt(su.getString("limitP2", Constants.DEFAULT_P2_LIMIT.toString()))
                limitTemp = Integer.parseInt(su.getString("limitTemp", Constants.DEFAULT_TEMP_LIMIT.toString()))
                limitHumidity = Integer.parseInt(su.getString("limitHumidity", Constants.DEFAULT_HUMIDITY_LIMIT.toString()))
                limitPressure = Integer.parseInt(su.getString("limitPressure", Constants.DEFAULT_PRESSURE_LIMIT.toString()))
            } catch (e: NumberFormatException) {
                limitP1 = Constants.DEFAULT_P1_LIMIT
                su.putString("limitP1", limitP1.toString())
                limitP2 = Constants.DEFAULT_P2_LIMIT
                su.putString("limitP2", limitP2.toString())
                limitTemp = Constants.DEFAULT_TEMP_LIMIT
                su.putString("limitTemp", limitTemp.toString())
                limitHumidity = Constants.DEFAULT_HUMIDITY_LIMIT
                su.putString("limitHumidity", limitHumidity.toString())
                limitPressure = Constants.DEFAULT_PRESSURE_LIMIT
                su.putString("limitPressure", limitPressure.toString())
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Get timestamps for 'from' and 'to'
                    val from = selectedDayTimestamp
                    val to = selectedDayTimestamp + TimeUnit.DAYS.toMillis(1)

                    val sensors = ArrayList<Sensor>()
                    sensors.addAll(su.allFavourites)
                    sensors.addAll(su.allOwnSensors)
                    for (s in sensors) {
                        // Load existing records from the local database
                        records = su.loadRecords(s.chipID, from, to)
                        // Sort by time
                        records!!.sort()
                        // Load records from server
                        val recordsExternal = loadDataRecords(applicationContext, s.chipID, if (records!!.size > 0) records!![records!!.size - 1].dateTime.time + 1000 else from, to)
                        if (recordsExternal != null) records!!.addAll(recordsExternal)
                        // Sort by time
                        records!!.sort()

                        if (records!!.size > 0) {
                            // Detect a breakdown
                            if (su.getBoolean("notification_breakdown", true) && su.isSensorExisting(s.chipID) && Tools.isMeasurementBreakdown(su, records!!)) {
                                if (recordsExternal != null && !su.getBoolean("BD_" + s.chipID)) {
                                    nu.displayMissingMeasurementsNotification(s.chipID, s.name)
                                    su.putBoolean("BD_" + s.chipID, true)
                                }
                            } else {
                                nu.cancelNotification(Integer.parseInt(s.chipID) * 10)
                                su.removeKey("BD_" + s.chipID)
                            }
                            // Calculate average values
                            val averageP1 = getP1Average(records!!)
                            val averageP2 = getP2Average(records!!)
                            val averageTemp = getTempAverage(records!!)
                            val averageHumidity = getHumidityAverage(records!!)
                            val averagePressure = getPressureAverage(records!!)
                            records = trimDataRecordsToSyncTime(s.chipID, records!!)
                            // Evaluate
                            for (r in records!!) {
                                if (!fromForeground && !su.getBoolean(selectedDayTimestamp.toString() + "_p1_exceeded") && limitP1 > 0 && (if (su.getBoolean("notification_averages", true)) averageP1 > limitP1 else r.p1 > limitP1) && r.p1 > su.getDouble(selectedDayTimestamp.toString() + "_p1_max")) {
                                    Log.i("FA", "P1 limit exceeded")
                                    // P1 notification
                                    nu.displayLimitExceededNotification(s.name + " - " + resources.getString(
                                        R.string.limit_exceeded_p1), s.chipID, r.dateTime.time)
                                    su.putDouble(selectedDayTimestamp.toString() + "_p1_max", r.p1)
                                    su.putBoolean(selectedDayTimestamp.toString() + "_p1_exceeded", true)
                                    break
                                } else if (limitP1 > 0 && r.p1 < limitP1) {
                                    su.putBoolean(selectedDayTimestamp.toString() + "_p1_exceeded", false)
                                }
                                if (!fromForeground && !su.getBoolean(selectedDayTimestamp.toString() + "_p2_exceeded") && limitP2 > 0 && (if (su.getBoolean("notification_averages", true)) averageP2 > limitP2 else r.p2 > limitP2) && r.p2 > su.getDouble(selectedDayTimestamp.toString() + "_p2_max")) {
                                    Log.i("FA", "P2 limit exceeded")
                                    // P2 notification
                                    nu.displayLimitExceededNotification(s.name + " - " + resources.getString(
                                        R.string.limit_exceeded_p2), s.chipID, r.dateTime.time)
                                    su.putDouble(selectedDayTimestamp.toString() + "_p2_max", r.p2)
                                    su.putBoolean(selectedDayTimestamp.toString() + "_p2_exceeded", true)
                                    break
                                } else if (limitP1 > 0 && r.p1 < limitP1) {
                                    su.putBoolean(selectedDayTimestamp.toString() + "_p2_exceeded", false)
                                }
                                if (!fromForeground && !su.getBoolean(selectedDayTimestamp.toString() + "_temp_exceeded") && limitTemp > 0 && (if (su.getBoolean("notification_averages", true)) averageTemp > limitTemp else r.temp > limitTemp) && r.temp > su.getDouble(selectedDayTimestamp.toString() + "_temp_max")) {
                                    Log.i("FA", "Temp limit exceeded")
                                    // Temperature notification
                                    nu.displayLimitExceededNotification(s.name + " - " + resources.getString(
                                        R.string.limit_exceeded_temp), s.chipID, r.dateTime.time)
                                    su.putDouble(selectedDayTimestamp.toString() + "_temp_max", r.temp)
                                    su.putBoolean(selectedDayTimestamp.toString() + "_temp_exceeded", true)
                                    break
                                } else if (limitP1 > 0 && r.p1 < limitP1) {
                                    su.putBoolean(selectedDayTimestamp.toString() + "_temp_exceeded", false)
                                }
                                if (!fromForeground && !su.getBoolean(selectedDayTimestamp.toString() + "_humidity_exceeded") && limitHumidity > 0 && (if (su.getBoolean("notification_averages", true)) averageHumidity > limitHumidity else r.humidity > limitHumidity) && r.humidity > su.getDouble(selectedDayTimestamp.toString() + "_humidity_max")) {
                                    Log.i("FA", "Humidity limit exceeded")
                                    // Humidity notification
                                    nu.displayLimitExceededNotification(s.name + " - " + resources.getString(
                                        R.string.limit_exceeded_humidity), s.chipID, r.dateTime.time)
                                    su.putDouble(selectedDayTimestamp.toString() + "_humidity_max", r.humidity)
                                    su.putBoolean(selectedDayTimestamp.toString() + "_humidity_exceeded", true)
                                    break
                                } else if (limitP1 > 0 && r.p1 < limitP1) {
                                    su.putBoolean(selectedDayTimestamp.toString() + "_humidity_exceeded", false)
                                }
                                if (!fromForeground && !su.getBoolean(selectedDayTimestamp.toString() + "_pressure_exceeded") && limitPressure > 0 && (if (su.getBoolean("notification_averages", true)) averagePressure > limitPressure else r.pressure > limitPressure) && r.humidity > su.getDouble(selectedDayTimestamp.toString() + "_pressure_max")) {
                                    Log.i("FA", "Pressure limit exceeded")
                                    // Pressure notification
                                    nu.displayLimitExceededNotification(s.name + " - " + resources.getString(
                                        R.string.limit_exceeded_pressure), s.chipID, r.dateTime.time)
                                    su.putDouble(selectedDayTimestamp.toString() + "_pressure_max", r.temp)
                                    su.putBoolean(selectedDayTimestamp.toString() + "_pressure_exceeded", true)
                                    break
                                } else if (limitP1 > 0 && r.p1 < limitP1) {
                                    su.putBoolean(selectedDayTimestamp.toString() + "_pressure_exceeded", false)
                                }
                            }

                            // Refresh homescreen widget
                            val updateIntent = Intent(applicationContext, WidgetProvider::class.java)
                            updateIntent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            updateIntent.putExtra(Constants.WIDGET_EXTRA_SENSOR_ID, s.chipID)
                            sendBroadcast(updateIntent)
                        }
                    }

                    if (params != null) jobFinished(params, false)
                } catch (e: Exception) {
                    if (params != null) jobFinished(params, true)
                }
            }
        } else {
            if (params != null) jobFinished(params, false)
        }
    }

    private fun getP1Average(records: ArrayList<DataRecord>): Double {
        var average = 0.0
        for (r in records) average += r.p1
        return average / records.size
    }

    private fun getP2Average(records: ArrayList<DataRecord>): Double {
        var average = 0.0
        for (r in records) average += r.p2
        return average / records.size
    }

    private fun getTempAverage(records: ArrayList<DataRecord>): Double {
        var average = 0.0
        for (r in records) average += r.temp
        return average / records.size
    }

    private fun getHumidityAverage(records: ArrayList<DataRecord>): Double {
        var average = 0.0
        for (r in records) average += r.humidity
        return average / records.size
    }

    private fun getPressureAverage(records: ArrayList<DataRecord>): Double {
        var average = 0.0
        for (r in records) average += r.pressure
        return average / records.size
    }

    private fun trimDataRecordsToSyncTime(chip_id: String, all_records: ArrayList<DataRecord>): ArrayList<DataRecord> {
        // Load last execution time
        val lastRecord = su.getLong(chip_id + "_LastRecord", System.currentTimeMillis())

        val records = ArrayList<DataRecord>()
        for (r in all_records) {
            if (r.dateTime.time > lastRecord) records.add(r)
        }

        // Save execution time
        if (all_records.size > 0) su.putLong(chip_id + "_LastRecord", all_records[all_records.size - 1].dateTime.time)

        return records
    }
}
