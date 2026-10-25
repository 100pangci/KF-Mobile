package work.miaola.kf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import work.miaola.kf.ui.KfDestinationKind
import work.miaola.kf.ui.KfDestinations
import work.miaola.kf.ui.isTopLevel
import work.miaola.kf.ui.KfSearchRoute

class KfDestinationsTest {
    @Test fun urlRoutesMapToExplicitDestinations() {
        assertEquals(KfDestinationKind.FEED, KfDestinations.fromUrl("https://kf.miaola.work/index.php"))
        assertEquals(KfDestinationKind.LISTING, KfDestinations.fromUrl("https://kf.miaola.work/thread.php?fid=5"))
        assertEquals(KfDestinationKind.READER, KfDestinations.fromUrl("https://kf.miaola.work/read.php?tid=1"))
        assertEquals(KfDestinationKind.EDITOR, KfDestinations.fromUrl("https://kf.miaola.work/post.php?action=quote&tid=1"))
        assertEquals(KfDestinationKind.EDITOR, KfDestinations.fromUrl("message.php?action=write&touid=2"))
        assertEquals(KfDestinationKind.SEARCH, KfDestinations.fromUrl("search.php?keyword=x"))
        assertEquals(KfDestinationKind.MESSAGES, KfDestinations.fromUrl("message.php?action=receivebox"))
        assertEquals(KfDestinationKind.MESSAGE, KfDestinations.fromUrl("message.php?action=read&mid=1"))
        assertEquals(KfDestinationKind.PROFILE, KfDestinations.fromUrl("profile.php?action=show&uid=1"))
        assertEquals(KfDestinationKind.LISTING, KfDestinations.fromUrl("profile.php?action=favor&page=2"))
        assertEquals(KfDestinationKind.MESSAGES, KfDestinations.fromUrl("message.php?memo=action=write"))
        assertEquals(KfDestinationKind.LOGIN, KfDestinations.fromUrl("login.php"))
        assertEquals(KfDestinationKind.NATIVE, KfDestinations.fromUrl("kf_growup.php"))
    }

    @Test fun searchRouteEscapesArgumentsAndResultUrlsIndependently() {
        assertEquals("search?q=%E7%BB%AF%E6%9C%88%20%26%3F%23%25&author=a%7Cb&url=", KfSearchRoute.create("绯月 &?#%", "a|b"))
        assertEquals("search?q=&author=&url=search.php%3Fsearchid%3Dabc%26page%3D2", KfSearchRoute.create(resultUrl = "search.php?searchid=abc&page=2"))
        assertTrue(KfDestinations.isSearchNavigation("search.php?searchid=abc&page=2"))
        assertTrue(KfDestinations.isSearchNavigation("search.php?step=2&searchid=abc&page=2"))
        assertFalse(KfDestinations.isSearchNavigation("search.php?step=2&keyword=x"))
        assertFalse(KfDestinations.isSearchNavigation("search.php?searchid=abc&action=delete"))
    }

    @Test fun messageBoxLinksNeverAskForAWriteConfirmation() {
        assertTrue(KfDestinations.isReadOnlyMessage("message.php?action=receivebox"))
        assertTrue(KfDestinations.isReadOnlyMessage("message.php?action=sendbox"))
        assertTrue(KfDestinations.isReadOnlyMessage("message.php?action=read&mid=1"))
        assertTrue(KfDestinations.isReadOnlyMessage("message.php?action=write&touid=1"))
        assertFalse(KfDestinations.isReadOnlyMessage("message.php?action=del&delid[]=1"))
        assertFalse(KfDestinations.isReadOnlyMessage("message.php?action=sendbox&step=2"))
        assertFalse(KfDestinations.isReadOnlyMessage("message.php?action=write&do=delete"))
        assertFalse(KfDestinations.isReadOnlyMessage("message.php?action=read&action=del&mid=1"))
    }

    @Test fun onlyRootTabsKeepBottomNavigation() {
        assertTrue(KfDestinationKind.FEED.isTopLevel)
        assertTrue(KfDestinationKind.BOARDS.isTopLevel)
        assertTrue(KfDestinationKind.MESSAGES.isTopLevel)
        assertTrue(KfDestinationKind.ACCOUNT.isTopLevel)
        assertFalse(KfDestinationKind.READER.isTopLevel)
        assertFalse(KfDestinationKind.LISTING.isTopLevel)
        assertFalse(KfDestinationKind.EDITOR.isTopLevel)
        assertFalse(KfDestinationKind.NATIVE.isTopLevel)
    }
}
