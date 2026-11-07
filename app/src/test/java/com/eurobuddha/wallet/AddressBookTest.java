package com.eurobuddha.wallet;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The configurable key count: chosen before the seed exists, persisted, grow-only, picked up by derive. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AddressBookTest {

    private static WalletCore wallet() {
        return new WalletCore("addressbook test seed phrase", new SeedVaultC1Test.InMemoryKeyUses());
    }

    @Test
    public void pendingCount_survivesFreshSeed_andGrowsOnly() {
        Context ctx = RuntimeEnvironment.getApplication();
        AddressBook.setPendingKeyCount(ctx, 2);

        AddressBook book = new AddressBook(ctx, wallet());   // new seed fingerprint → cache reset
        assertEquals("onboarding count carried across the reset", 2, book.size());
        assertFalse(book.isComplete());

        assertEquals(2, book.deriveMissing());
        assertTrue(book.isComplete());
        assertNotNull(book.addrMx(1));
        assertNull("beyond size", book.addrMx(2));

        book.setSize(1);
        assertEquals("never shrinks", 2, book.size());
        book.setSize(3);
        assertEquals(3, book.size());
        assertFalse(book.isComplete());
        assertEquals("only the new key is derived", 1, book.deriveMissing());
        assertEquals(3, book.derivedCount());

        AddressBook again = new AddressBook(ctx, wallet());   // same seed → warm cache
        assertEquals("size persisted", 3, again.size());
        assertEquals("pubkeys persisted", 3, again.derivedCount());
        assertEquals(0, again.deriveMissing());
        assertEquals(book.addrMx(2), again.addrMx(2));
    }

    @Test
    public void defaultIsTheNodeDefault_andClamped() {
        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("addressbook", Context.MODE_PRIVATE).edit().clear().apply();
        assertEquals(64, new AddressBook(ctx, wallet()).size());
        AddressBook.setPendingKeyCount(ctx, 99999);
        assertEquals(AddressBook.MAX_SIZE, new AddressBook(ctx, wallet()).size());
    }
}
