package de.dml.rezeptimporter.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHostsTest {
    @Test fun tiktok() = assertTrue(LinkHosts.isSocial("https://www.tiktok.com/@koch/video/123"))
    @Test fun tiktokShort() = assertTrue(LinkHosts.isSocial("https://vm.tiktok.com/ZN123/"))
    @Test fun instagram() = assertTrue(LinkHosts.isSocial("https://www.instagram.com/reel/Cxyz123/"))
    @Test fun webBlogNotSocial() =
        assertFalse(LinkHosts.isSocial("https://www.chefkoch.de/rezepte/123/curry.html"))

    @Test fun tiktokIsTikTokNotInstagram() {
        assertTrue(LinkHosts.isTikTok("https://www.tiktok.com/@x/video/1"))
        assertFalse(LinkHosts.isInstagram("https://www.tiktok.com/@x/video/1"))
    }
}
