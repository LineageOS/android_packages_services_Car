# VHAL native client library.

'client' folder contains the VHAL native client library: 'libvhalclient'. It
is a C++ client library for VHAL. It is designed to provide a common interface:
'IVhalClient' for both AIDL and HIDL VHAL so that client do not need to worry
about compatibility.

## Sample usage:

```
// Waits for VHAL service and creates a client. Returns nullptr if failed to connect to VHAL.
// Uses tryCreate for non-blocking version.
std::shared_ptr<IVhalClient> vhalClient = IVhalClient::create(/*startThreadPool=*/false);
if (vhalClient == nullptr) {
	// Failed to connect to VHAL.
	return;
}

// It is recommended to explicitly start thread pool at the caller side. This is required to
// receive async callbacks through the client's binder threads.
ABinderProcess_startThreadPool();

// For setting value

// IHalPropValue is the interface for a VHAL property value.
std::unique_ptr<IHalPropValue> valueToSet = vhalClient->createHalPropValue(propId, areaId);
valueToSet->setInt32Values({1, 2, 3});
// Sets the value synchronously, use setValue for async version.
VhalClientResult<void> setResult = vhalClient->setValueSync(*valueToSet);
if (!setResult.ok()) {
	// Failed to set value.
	return;
}

// For getting value

VhalClientResult<std::unique_ptr<IHalPropValue>> getResult =
		vhalClient->getValueSync(*vhalClient->crateHalPropValue(propId));

// Must check the property status here. If the property status is not AVAILABLE, the property
// value must not be used.
if (!result.ok() || result.value()->getStatus() != VehiclePropertyStatus::AVAILABLE) {
	// Failed to get value.
	return;
}
// We assume this is an Integer-type property.
int intPropValue = result.value()->getInt32Values()[0];

// For subscribing to value change events.

std::unique_ptr<ISubscriptionClient> subscriptionClient = vhalClient->getSubscriptionClient(
		getCallback());
auto subscribeResult = subscriptionClient->subscribe(
		{{.propId = propId, .sampleRate = minSampleRate}});
if (!subscribeResult.ok()) {
	// Failed to subscribe.
	return;
}

// Finally, the client must unsubscribe the property events. Simply destroying the subscription
// client will not cause all the subscribed property events to be unsubscribed.
subscriptionClient->unsubscribeAll();
```