package info.nightscout.androidaps.plugins.general.tidepool

import android.text.Html
import android.text.Spanned
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.MainApp
import info.nightscout.androidaps.R
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.events.EventNetworkChange
import info.nightscout.androidaps.events.EventNewBG
import info.nightscout.androidaps.events.EventPreferenceChange
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.logging.L
import info.nightscout.androidaps.plugins.general.tidepool.comm.TidepoolUploader
import info.nightscout.androidaps.plugins.general.tidepool.events.EventTidepoolDoUpload
import info.nightscout.androidaps.plugins.general.tidepool.events.EventTidepoolResetData
import info.nightscout.androidaps.plugins.general.tidepool.events.EventTidepoolStatus
import info.nightscout.androidaps.plugins.general.tidepool.events.EventTidepoolUpdateGUI
import info.nightscout.androidaps.plugins.general.tidepool.utils.RateLimit
import info.nightscout.androidaps.receivers.ChargingStateReceiver
import info.nightscout.androidaps.receivers.NetworkChangeReceiver
import info.nightscout.androidaps.utils.SP
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.ToastUtils
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import java.util.*


object TidepoolPlugin : PluginBase(PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.tidepool)
        .shortName(R.string.tidepool_shortname)
        .fragmentClass(TidepoolJavaFragment::class.java.name)
        .preferencesId(R.xml.pref_tidepool)
        .description(R.string.description_tidepool)
) {
    private val log = LoggerFactory.getLogger(L.TIDEPOOL)
    private var disposable: CompositeDisposable = CompositeDisposable()

    private val listLog = ArrayList<EventTidepoolStatus>()
    @Suppress("DEPRECATION") // API level 24 to replace call
    var textLog: Spanned = Html.fromHtml("")

    operator fun CompositeDisposable.plusAssign(disposable: Disposable) {
        add(disposable)
    }

    override fun onStart() {
        super.onStart()
        disposable += RxBus
                .toObservable(EventTidepoolDoUpload::class.java)
                .observeOn(Schedulers.io())
                .subscribe({ doUpload() }, {})
        disposable += RxBus
                .toObservable(EventTidepoolResetData::class.java)
                .observeOn(Schedulers.io())
                .subscribe({
                    if (TidepoolUploader.connectionStatus != TidepoolUploader.ConnectionStatus.CONNECTED) {
                        log.debug("Not connected for delete Dataset")
                    } else {
                        TidepoolUploader.deleteDataSet()
                        SP.putLong(R.string.key_tidepool_last_end, 0)
                        TidepoolUploader.doLogin()
                    }
                }, {})
        disposable += RxBus
                .toObservable(EventTidepoolStatus::class.java)
                .observeOn(Schedulers.io())
                .subscribe({ event -> addToLog(event) }, {})
        disposable += RxBus
                .toObservable(EventNewBG::class.java)
                .observeOn(Schedulers.io())
                .subscribe({ event ->
                    if (event.bgReading!!.date < TidepoolUploader.getLastEnd())
                        TidepoolUploader.setLastEnd(event.bgReading.date)
                    if (isEnabled(PluginType.GENERAL)
                            && (!SP.getBoolean(R.string.key_tidepool_only_while_charging, false) || ChargingStateReceiver.isCharging())
                            && (!SP.getBoolean(R.string.key_tidepool_only_while_unmetered, false) || NetworkChangeReceiver.isWifiConnected())
                            && RateLimit.rateLimit("tidepool-new-data-upload", T.mins(4).secs().toInt()))
                        doUpload()
                }, {})
        disposable += RxBus
                .toObservable(EventPreferenceChange::class.java)
                .observeOn(Schedulers.io())
                .subscribe({ event ->
                    if (event.isChanged(R.string.key_tidepool_dev_servers)
                            || event.isChanged(R.string.key_tidepool_username)
                            || event.isChanged(R.string.key_tidepool_password)
                    )
                        TidepoolUploader.resetInstance()
                }, {})
        disposable += RxBus
                .toObservable(EventNetworkChange::class.java)
                .observeOn(Schedulers.io())
                .subscribe({}, {}) // TODO start upload on wifi connect

    }

    override fun onStop() {
        disposable.clear()
        super.onStop()
    }

    private fun doUpload() {
        if (TidepoolUploader.connectionStatus == TidepoolUploader.ConnectionStatus.DISCONNECTED)
            TidepoolUploader.doLogin(true)
        else
            TidepoolUploader.doUpload()
    }

    @Synchronized
    private fun addToLog(ev: EventTidepoolStatus) {
        synchronized(listLog) {
            listLog.add(ev)
            // remove the first line if log is too large
            if (listLog.size >= Constants.MAX_LOG_LINES) {
                listLog.removeAt(0)
            }
        }
        MainApp.bus().post(EventTidepoolUpdateGUI())
    }

    @Synchronized
    fun updateLog() {
        try {
            val newTextLog = StringBuilder()
            synchronized(listLog) {
                for (log in listLog) {
                    newTextLog.append(log.toPreparedHtml())
                }
            }
            @Suppress("DEPRECATION") // API level 24 to replace call
            textLog = Html.fromHtml(newTextLog.toString())
        } catch (e: OutOfMemoryError) {
            ToastUtils.showToastInUiThread(MainApp.instance().applicationContext, "Out of memory!\nStop using this phone !!!", R.raw.error)
        }
    }

}