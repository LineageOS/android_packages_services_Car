package android.car.vms;

import android.car.annotation.FutureFeature;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Records VMS operations using the Android Log.
 *
 * This class records VMS operations. The recorded messages include the VMS operations and its
 * arguments encoded as JSON text so that the string can be both read as a log message and easily
 * parsed. VmsOperationRecorder is intended to be called after successful state change.
 *
 * Access the VmsOperationRecorder using the {@link #get()} method, which returns a singleton
 * instance. Each VMS operation has a corresponding VmsOperationRecorder method. For instance:
 * <pre>{@code
 *   VmsOperationRecorder.get().subscribe(layer);
 * }</pre>
 *
 * @hide
 */
@FutureFeature
public final class VmsOperationRecorder {
    private static final String TAG = "VmsOperationRecorder";
    private static final VmsOperationRecorder INSTANCE = new VmsOperationRecorder(new Writer());
    private final Writer mWriter;

    @VisibleForTesting
    public VmsOperationRecorder(Writer writer) {
        mWriter = writer;
    }

    /** Return the singleton instance. */
    public static VmsOperationRecorder get() {
        return INSTANCE;
    }

    // VMS Client operations.

    public void subscribe(VmsLayer layer) {
        recordOp("subscribe", layer);
    }

    public void unsubscribe(VmsLayer layer) {
        recordOp("unsubscribe", layer);
    }

    public void subscribeAll() {
        recordOp("subscribeAll");
    }

    public void unsubscribeAll() {
        recordOp("unsubscribeAll");
    }

    public void setLayersOffering(VmsLayersOffering layersOffering) {
        recordOp("setLayersOffering", layersOffering);
    }

    public void getPublisherStaticId(int publisherStaticId) {
        recordOp("getPublisherStaticId", "publisherStaticId", publisherStaticId);
    }

    // VMS Service operations.

    public void addSubscription(int sequenceNumber, VmsLayer layer) {
        recordOp("addSubscription", "sequenceNumber", sequenceNumber, layer);
    }

    public void removeSubscription(int sequenceNumber, VmsLayer layer) {
        recordOp("removeSubscription", "sequenceNumber", sequenceNumber, layer);
    }

    public void addPromiscuousSubscription(int sequenceNumber) {
        recordOp("addPromiscuousSubscription", "sequenceNumber", sequenceNumber);
    }

    public void removePromiscuousSubscription(int sequenceNumber) {
        recordOp("removePromiscuousSubscription", "sequenceNumber", sequenceNumber);
    }

    public void addHalSubscription(int sequenceNumber, VmsLayer layer) {
        recordOp("addHalSubscription", "sequenceNumber", sequenceNumber, layer);
    }

    public void removeHalSubscription(int sequenceNumber, VmsLayer layer) {
        recordOp("removeHalSubscription", "sequenceNumber", sequenceNumber, layer);
    }

    public void setPublisherLayersOffering(VmsLayersOffering layersOffering) {
        recordOp("setPublisherLayersOffering", layersOffering);
    }

    public void setHalPublisherLayersOffering(VmsLayersOffering layersOffering) {
        recordOp("setHalPublisherLayersOffering", layersOffering);
    }

    private void recordOp(String operation) {
        if (isEnabled()) {
            try {
                write(new JSONObject().put(operation, new JSONObject()));
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private void recordOp(String operation, VmsLayer layer) {
        if (isEnabled()) {
            try {
                recordOp(operation, new JSONObject().put("layer", toJson(layer)));
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private void recordOp(String operation, VmsLayersOffering layersOffering) {
        if (isEnabled()) {
            try {
                JSONObject args = new JSONObject();
                JSONArray offering = toJson(layersOffering);
                if (offering.length() > 0) {
                    args.put("layerDependency", offering);
                }
                recordOp(operation, args);
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private void recordOp(String operation, String intArgName, int arg) {
        if (isEnabled()) {
            try {
                recordOp(operation, new JSONObject().put(intArgName, arg));
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private void recordOp(String operation, String intArgName, int arg, VmsLayer layer) {
        if (isEnabled()) {
            try {
                recordOp(operation,
                        new JSONObject().put(intArgName, arg).put("layer", toJson(layer)));
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private void recordOp(String operation, JSONObject args) {
        if (isEnabled()) {
            try {
                write(new JSONObject().put(operation, args));
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private static JSONObject toJson(VmsLayer layer) throws JSONException {
        return new JSONObject()
                .put("id", layer.getId())
                .put("version", layer.getVersion())
                .put("subtype", layer.getSubType());
    }

    private static JSONObject toJson(VmsLayerDependency layerDependency) throws JSONException {
        JSONObject dep = new JSONObject();
        dep.put("layer", toJson(layerDependency.getLayer()));
        if (!layerDependency.getDependencies().isEmpty()) {
            JSONArray dependencies = new JSONArray();
            for (VmsLayer dependency : layerDependency.getDependencies()) {
                dependencies.put(toJson(dependency));
            }
            dep.put("dependency", dependencies);
        }
        return dep;
    }

    private static JSONArray toJson(VmsLayersOffering layersOffering) throws JSONException {
        JSONArray offerings = new JSONArray();
        for (VmsLayerDependency layerDependency : layersOffering.getDependencies()) {
            offerings.put(toJson(layerDependency));
        }
        return offerings;
    }

    private boolean isEnabled() {
        return mWriter.isEnabled();
    }

    private void write(JSONObject object) {
        mWriter.write(object.toString());
    }

    @VisibleForTesting
    public static class Writer {
        private static final String TAG = "VMS.RECORD.EVENT";
        private static final int LEVEL = Log.DEBUG;

        public boolean isEnabled() {
            return Log.isLoggable(TAG, LEVEL);
        }

        public void write(String msg) {
            Log.println(LEVEL, TAG, msg);
        }
    }
}
