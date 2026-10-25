package work.miaola.kf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import work.miaola.kf.ui.KfDestinationKind
import work.miaola.kf.ui.KfDestinations
import work.miaola.kf.ui.isTopLevel

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
        assertEquals(KfDestinationKind.LOGIN, KfDestinations.fromUrl("login.php"))
        assertEquals(KfDestinationKind.NATIVE, KfDestinations.fromUrl("kf_growup.php"))
    }

    @Test fun messageBoxLinksNeverAskForAWriteConfirmation() {
        assertTrue(KfDestinations.isReadOnlyMessage("message.php?action=receivebox"))
        assertTrue(KfDestinations.isReadOnlyMessage("message.php?action=sendbox"))
        assertTrue(KfDestinations.isReadOnlyMessage("message.php?action=read&mid=1"))
        assertTrue(KfDestinations.isReadOnlyMessage("message.php?action=write&touid=1"))
        assertFalse(KfDestinations.isReadOnlyMessage("message.php?action=del&delid[]=1"))
        assertFalse(KfDestinations.isReadOnlyMessage("message.php?action=sendbox&step=2"))
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
