package com.google.android.car.pbapsink;

import com.android.vcard.VCardEntry;

import android.accounts.Account;
import android.bluetooth.client.pbap.BluetoothPbapClient;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.OperationApplicationException;
import android.provider.ContactsContract;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.RawContactsEntity;
import android.provider.ContactsContract.Contacts.Entity;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.util.Log;

import com.android.vcard.VCardEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PhonebookPullRequest extends PullRequest {
    private static final int MAX_OPS = 200;
    private static final boolean DBG = true;
    private static final String TAG = "PbapPhonebookPullRequest";

    private final Account mAccount;
    private final Context mContext;
    public boolean complete = false;

    public PhonebookPullRequest(Context context, Account account) {
        mContext = context;
        mAccount = account;
        path = BluetoothPbapClient.PB_PATH;
    }

    // TODO: Apply operations together if possible.
    private void addContact(VCardEntry e)
        throws RemoteException, OperationApplicationException {
        ArrayList<ContentProviderOperation> ops =
                e.constructInsertOperations(mContext.getContentResolver(), null);
        mContext.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        ops.clear();
    }

    @Override
    public void onPullComplete(boolean success, List<VCardEntry> entries) {
        if (entries == null) {
            Log.e(TAG, "onPullComplete entries is null.");
            return;
        }

        if (DBG) {
            Log.d(TAG, "onPullComplete with " + entries.size() + " count.");
        }
        try {
            if (!success) {
                Log.e(TAG, "Pull finished with errors.");
                return;
            }

            for (VCardEntry e : entries) {
                addContact(e);
            }
            Log.d(TAG, "Sync complete: add=" + entries.size());
        } catch (OperationApplicationException | RemoteException | NumberFormatException e) {
            Log.d(TAG, "Got exception: ", e);
        } finally {
            complete = true;
        }
    }
}
