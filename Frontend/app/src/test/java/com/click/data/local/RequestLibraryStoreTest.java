package com.click.data.local;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.mockito.MockedConstruction;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RequestLibraryStoreTest {
    @Test public void arbitraryDropPositionPersistsIndependentlyForEachTab() {
        try (MockedConstruction<AuthTokenStore> auth = mockConstruction(AuthTokenStore.class)) {
            RequestLibraryStore store = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            java.util.List<String> ids = java.util.List.of("help:a", "request:b", "help:c", "request:d");
            store.moveToPosition("request:d", 1, ids, false);
            RequestLibraryStore reopened = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            assertEquals(java.util.List.of("help:a", "request:d", "request:b", "help:c"), reopened.orderedIds(ids, false));
            assertEquals(ids, reopened.orderedIds(ids, true));
            reopened.moveToPosition("help:a", 3, ids, false);
            assertEquals(java.util.List.of("request:d", "request:b", "help:c", "help:a"), store.orderedIds(ids, false));
        }
    }

    @Test public void deletingRecentHistoryKeepsFavoritesAndNewUseRestoresHistory() {
        try (MockedConstruction<AuthTokenStore> auth = mockConstruction(AuthTokenStore.class)) {
            RequestLibraryStore store = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            store.record("보관할 기록", "settings.font_size.increase");
            String saved = store.entries(false).get(0).id;
            store.setFavorite(saved, true);
            store.removeRecent(saved);
            RequestLibraryStore reopened = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            assertTrue(reopened.entries(false).isEmpty());
            assertEquals(saved, reopened.entries(true).get(0).id);
            reopened.record("보관할 기록", null);
            assertEquals(saved, reopened.entries(false).get(0).id);
            assertTrue(reopened.entries(false).get(0).favorite);
            reopened.record("지울 기록", null);
            String removed = reopened.entries(false).get(0).id;
            reopened.removeRecent(removed);
            assertEquals(1, reopened.entries(false).size());
            assertEquals(saved, reopened.entries(false).get(0).id);
        }
    }
    @Test public void repeatedRequestMovesToTopAndKeepsItsStarAfterReopen() {
        try (MockedConstruction<AuthTokenStore> auth = mockConstruction(AuthTokenStore.class)) {
            RequestLibraryStore store = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            store.record("화면 밝기 낮춰줘", "settings.brightness.decrease");
            String id = store.entries(false).get(0).id;
            store.setFavorite(id, true);
            store.record("글자 크기 키워줘", null);
            store.record("화면 밝기 낮춰줘", null);
            RequestLibraryStore reopened = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            assertEquals(2, reopened.entries(false).size());
            assertEquals(id, reopened.entries(false).get(0).id);
            assertEquals("settings.brightness.decrease", reopened.entries(true).get(0).goalType);
            reopened.setFavorite(id, false);
            assertTrue(reopened.entries(true).isEmpty());
            assertEquals(2, reopened.entries(false).size());
        }
    }
    @Test public void olderSavedRequestSurvivesRecentLimit() {
        try (MockedConstruction<AuthTokenStore> auth = mockConstruction(AuthTokenStore.class)) {
            RequestLibraryStore store = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            store.record("보관할 질문", null);
            store.setFavorite(store.entries(false).get(0).id, true);
            for (int i = 0; i < 60; i++) store.record("질문 " + i, null);
            assertEquals(50, store.entries(false).size());
            assertEquals("질문 59", store.entries(false).get(0).request);
            assertEquals("보관할 질문", store.entries(true).get(0).request);
        }
    }
    @Test public void anotherAccountCannotSeePreviousAccountsQuestions() {
        String[] user = {"account-one"};
        try (MockedConstruction<AuthTokenStore> auth = mockConstruction(AuthTokenStore.class,
                (mock, context) -> when(mock.userId()).thenReturn(user[0]))) {
            new RequestLibraryStore(RuntimeEnvironment.getApplication()).record("첫 계정의 질문", null);
            user[0] = "account-two";
            assertTrue(new RequestLibraryStore(RuntimeEnvironment.getApplication()).entries(false).isEmpty());
            user[0] = "account-one";
            assertEquals(1, new RequestLibraryStore(RuntimeEnvironment.getApplication()).entries(false).size());
        }
    }
    @Test public void mixedFavoriteOrderPersistsAndDoesNotChangeRecentOrder() {
        try (MockedConstruction<AuthTokenStore> auth = mockConstruction(AuthTokenStore.class)) {
            RequestLibraryStore store = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            java.util.List<String> ids = java.util.List.of("help:font_size", "help:brightness", "request:one");
            store.moveToTop("request:one", ids, false);
            RequestLibraryStore reopened = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            assertEquals(java.util.List.of("request:one", "help:font_size", "help:brightness"), reopened.orderedIds(ids, false));
            assertEquals(ids, reopened.orderedIds(ids, true));
            reopened.moveToTop("help:brightness", ids, false);
            assertEquals(java.util.List.of("help:brightness", "request:one", "help:font_size"), store.orderedIds(ids, false));
            assertEquals(java.util.List.of("help:font_size", "request:new"),
                    store.orderedIds(java.util.List.of("help:font_size", "request:new"), false));
        }
    }

    @Test public void recentDisplayReorderingDoesNotRewriteHistoryOrEvictNewRequests() {
        try (MockedConstruction<AuthTokenStore> auth = mockConstruction(AuthTokenStore.class)) {
            RequestLibraryStore store = new RequestLibraryStore(RuntimeEnvironment.getApplication());
            store.record("이전 질문", null);
            String older = store.entries(false).get(0).id;
            store.record("새 질문", null);
            String newer = store.entries(false).get(0).id;
            java.util.List<String> ids = java.util.List.of("request:" + newer, "request:" + older);
            store.moveToTop("request:" + older, ids, true);
            assertEquals("request:" + older, store.orderedIds(ids, true).get(0));
            assertEquals(newer, store.entries(false).get(0).id);
            store.moveToTop("missing", ids, true);
            assertEquals("request:" + older, store.orderedIds(ids, true).get(0));
        }
    }

}
