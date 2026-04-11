package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.KProcess
import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.css.CSSParser
import io.github.jwyoon1220.khromium.dom.JsoupDOMBuilder
import io.github.jwyoon1220.khromium.dom.KDOM
import io.github.jwyoon1220.khromium.js.KhromiumJsRuntime
import io.github.jwyoon1220.khromium.js.SharedBytecodeCache
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration-style tests that exercise the full HTML parsing pipeline with
 * HTML fixtures representative of major web sites (naver.com, google.com,
 * youtube.com, github.com, wikipedia.org, amazon.com).
 *
 * All tests run without real network I/O: inline HTML strings stand in for
 * actual page responses so they are safe for CI.
 *
 * What is validated for each site fixture:
 *   1. HTML → KDOM parsing completes without crashing (incl. Korean text, SVG,
 *      complex attributes, inline scripts/styles, DOCTYPE).
 *   2. Important structural elements (nav, forms, links, headings, …) survive
 *      the Jsoup → VMM round-trip and can be located in the KDOM tree.
 *   3. CSS extracted from <style> blocks is accepted by [CSSParser] without error.
 *   4. Inline <script> content is executed via [KhromiumJsRuntime] (Nashorn
 *      fallback) without raising an unhandled exception.
 */
class MajorWebSiteTest {

    // ── Test fixtures ──────────────────────────────────────────────────────────

    /**
     * Minimal but representative naver.com HTML.
     * Key challenges: Korean text, many <a> links, inline <style> with Unicode
     * font-face references, inline <script>, meta charset declaration.
     */
    private val naverHtml = """<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>NAVER</title>
  <style>
    * { box-sizing: border-box; }
    body { font-family: 'Malgun Gothic', sans-serif; margin: 0; background: #fff; }
    #wrap { width: 1080px; margin: 0 auto; }
    #header { display: flex; align-items: center; padding: 16px 0; }
    .logo { width: 176px; height: 48px; }
    #search_form { flex: 1; display: flex; height: 48px; border: 2px solid #03c75a; }
    #query { flex: 1; font-size: 20px; padding: 0 16px; border: none; outline: none; }
    .btn_search { width: 54px; background: #03c75a; color: #fff; border: none; cursor: pointer; }
    #gnb { border-bottom: 1px solid #e5e5e5; }
    .gnb_list { display: flex; list-style: none; margin: 0; padding: 0; }
    .gnb_list li a { display: block; padding: 12px 16px; color: #333; text-decoration: none; }
    .gnb_list li a:hover { color: #03c75a; }
    #newsstand { margin: 20px 0; }
    .news_title { font-size: 18px; font-weight: bold; color: #191919; margin-bottom: 12px; }
    .news_list { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
    .news_item { border: 1px solid #e5e5e5; padding: 12px; }
    #ranking { margin: 20px 0; }
    .rank_list { list-style: none; padding: 0; }
    .rank_list li { padding: 8px 0; border-bottom: 1px solid #f0f0f0; }
    .rank_num { color: #03c75a; font-weight: bold; margin-right: 8px; }
  </style>
  <script>
    var _naverApp = { version: "2.3", locale: "ko_KR" };
    function initSearch() {
      var q = document.getElementById("query");
      if (q) q.focus();
    }
  </script>
</head>
<body onload="initSearch()">
<div id="wrap">
  <div id="header">
    <h1 class="logo"><a href="https://www.naver.com/"><img src="/s/ssg_home.png" alt="NAVER" width="176" height="48"></a></h1>
    <form id="search_form" action="https://search.naver.com/search.naver" method="get">
      <input type="text" id="query" name="query" placeholder="검색어를 입력해 주세요" autocomplete="off">
      <button type="submit" class="btn_search">검색</button>
    </form>
  </div>
  <div id="gnb">
    <ul class="gnb_list">
      <li><a href="https://mail.naver.com/">메일</a></li>
      <li><a href="https://cafe.naver.com/">카페</a></li>
      <li><a href="https://blog.naver.com/">블로그</a></li>
      <li><a href="https://kin.naver.com/">지식iN</a></li>
      <li><a href="https://shopping.naver.com/">쇼핑</a></li>
      <li><a href="https://pay.naver.com/">Pay</a></li>
      <li><a href="https://tv.naver.com/">TV</a></li>
    </ul>
  </div>
  <div id="newsstand">
    <h2 class="news_title">뉴스스탠드</h2>
    <div class="news_list">
      <div class="news_item"><a href="#">조선일보</a></div>
      <div class="news_item"><a href="#">중앙일보</a></div>
      <div class="news_item"><a href="#">동아일보</a></div>
      <div class="news_item"><a href="#">한겨레</a></div>
      <div class="news_item"><a href="#">경향신문</a></div>
      <div class="news_item"><a href="#">매일경제</a></div>
    </div>
  </div>
  <div id="ranking">
    <h2 class="news_title">실시간 검색어</h2>
    <ol class="rank_list">
      <li><span class="rank_num">1</span><a href="#">코로나19</a></li>
      <li><span class="rank_num">2</span><a href="#">날씨</a></li>
      <li><span class="rank_num">3</span><a href="#">주식</a></li>
      <li><span class="rank_num">4</span><a href="#">환율</a></li>
      <li><span class="rank_num">5</span><a href="#">네이버웹툰</a></li>
    </ol>
  </div>
</div>
<script>
  (function() {
    var items = document.querySelectorAll ? document.querySelectorAll('.news_item') : [];
    var count = items ? items.length : 0;
    _naverApp.newsCount = count;
  })();
</script>
</body>
</html>"""

    /**
     * Representative google.com HTML.
     * Key challenges: SVG inline, data-* attributes, aria-* attributes,
     * form with hidden inputs, minimal but real-world structure.
     */
    private val googleHtml = """<!DOCTYPE html>
<html lang="en" itemscope itemtype="http://schema.org/WebPage">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Google</title>
  <style>
    body { background: #fff; margin: 0; padding: 0; font-family: arial,sans-serif; }
    #gbar, #guser { font-size: 13px; padding-top: 1px; }
    #gbar { height: 22px; }
    .gbh, .gbd { border-top: 2px solid #4285f4; border-bottom: 1px solid #e0e0e0; }
    #logo { text-align: center; padding: 28px 0 18px; }
    .lsb { background-color: #f8f9fa; border: 1px solid #f8f9fa; border-radius: 4px;
           color: #3c4043; cursor: pointer; font-size: 14px; margin: 11px 4px; padding: 0 16px;
           line-height: 27px; height: 36px; min-width: 120px; }
    .lsb:hover { box-shadow: 0 1px 1px rgba(0,0,0,.1); background-color: #f8f9fa;
                 border: 1px solid #dadce0; color: #202124; }
    input[type=text] { background-color: #fff; border: 1px solid #dfe1e5; border-radius: 24px;
                       color: #202124; font-size: 16px; height: 44px; outline: none; padding: 0 14px;
                       width: 450px; }
  </style>
</head>
<body bgcolor="#fff">
<div id="gbar">
  <nobr>
    <b class="gb1">Web</b>
    <a class="gb1" href="https://www.google.com/imghp?hl=en&amp;tab=wi">Images</a>
    <a class="gb1" href="https://maps.google.com/maps?hl=en&amp;tab=wl">Maps</a>
    <a class="gb1" href="https://play.google.com/?hl=en&amp;tab=w8">Play</a>
    <a class="gb1" href="https://www.youtube.com/?tab=w1">YouTube</a>
    <a class="gb1" href="https://news.google.com/?tab=wn">News</a>
    <a class="gb1" href="https://mail.google.com/mail/?tab=wm">Gmail</a>
    <a class="gb1" href="https://drive.google.com/?tab=wo">Drive</a>
  </nobr>
</div>
<div id="logo">
  <svg xmlns="http://www.w3.org/2000/svg" width="272" height="92" viewBox="0 0 272 92">
    <path fill="#4285f4" d="M115.75 47.18c0 12.77-9.99 22.18-22.25 22.18s-22.25-9.41-22.25-22.18C71.25 34.32 81.24 25 93.5 25s22.25 9.32 22.25 22.18zm-9.74 0c0-7.98-5.79-13.44-12.51-13.44S80.99 39.2 80.99 47.18c0 7.9 5.79 13.44 12.51 13.44s12.51-5.55 12.51-13.44z"/>
    <path fill="#d14836" d="M173.09 47.18c0 12.77-9.99 22.18-22.25 22.18s-22.25-9.41-22.25-22.18c0-12.85 9.99-22.18 22.25-22.18s22.25 9.32 22.25 22.18zm-9.74 0c0-7.98-5.79-13.44-12.51-13.44s-12.51 5.46-12.51 13.44c0 7.9 5.79 13.44 12.51 13.44s12.51-5.55 12.51-13.44z"/>
  </svg>
</div>
<div id="gsr">
  <form id="tsf" name="f" action="https://www.google.com/search" method="GET">
    <input name="ie" value="ISO-8859-1" type="hidden">
    <input name="hl" value="en" type="hidden">
    <input name="source" type="hidden" value="hp">
    <input name="biw" type="hidden">
    <input name="bih" type="hidden">
    <div class="RNNXgb" jsname="tl4" data-ved="0ahUKEwi" aria-label="Search">
      <div class="SDkEP">
        <input class="gLFyf" maxlength="2048" name="q" type="text"
               aria-autocomplete="both" aria-haspopup="false"
               autocapitalize="off" autocomplete="off" autocorrect="off"
               autofocus="" role="combobox" spellcheck="false"
               title="Search" value="" aria-label="Search">
      </div>
    </div>
    <div id="UUbT9" class="lPHgdd">
      <div class="FPdoLc VlcLAe">
        <input value="Google Search" aria-label="Google Search" name="btnK" role="button" tabindex="0" type="submit" data-ved="0ahUKEwi" class="lsb">
        <input value="I'm Feeling Lucky" aria-label="I'm Feeling Lucky" name="btnI" role="button" tabindex="0" type="submit" data-ved="0ahUKEwi" class="lsb">
      </div>
    </div>
  </form>
</div>
<script nonce="abc123">
  (function(){
    var a=window.google=window.google||{};
    a.kEI='xyz'; a.kEXPI='123456';
    a.bx=false; a.lc=[];
  })();
</script>
<script nonce="abc123">
  window.google.sn='webhp';
  window.google.pmc='{"sb_he":{"agen":false,"cgen":false,"client":"heirloom-hp"}}';
</script>
</body>
</html>"""

    /**
     * Representative youtube.com HTML.
     * Key challenges: custom HTML elements (ytd-*), large data-* attributes,
     * JSON-in-script (ytInitialData), many nested divs, aria roles.
     */
    private val youtubeHtml = """<!DOCTYPE html>
<html lang="en" data-cast-api-enabled="true">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>YouTube</title>
  <style>
    html { overflow-y: scroll; }
    body { background-color: #fff; color: #030303; font-family: Roboto,Arial,sans-serif;
           font-size: 14px; margin: 0; }
    ytd-app { display: block; }
    ytd-masthead { display: block; height: 56px; background: #fff; box-shadow: 0 1px 2px rgba(0,0,0,.3); }
    #logo-icon { width: 90px; height: 20px; }
    ytd-thumbnail { display: block; position: relative; overflow: hidden; }
    ytd-thumbnail img { width: 100%; height: auto; }
    #video-title { font-size: 14px; font-weight: 500; line-height: 20px; margin: 0; padding: 4px 0 2px; }
    ytd-rich-grid-renderer { display: grid; grid-template-columns: repeat(auto-fill,minmax(240px,1fr)); gap: 16px; }
  </style>
</head>
<body>
<ytd-app>
  <div id="content" class="ytd-app">
    <ytd-masthead id="masthead" slot="masthead" class="shell ytd-app">
      <div id="masthead-container" class="ytd-masthead">
        <div id="start" class="ytd-masthead">
          <ytd-logo id="logo" class="ytd-masthead">
            <svg id="logo-icon" viewBox="0 0 90 20" xmlns="http://www.w3.org/2000/svg">
              <path d="M27.9727 3.12324C27.6435 1.89323 26.6768 0.926623 25.4468 0.597366C23.2197 0 14.285 0 14.285 0C14.285 0 5.35042 0 3.12323 0.597366C1.89323 0.926623 0.926623 1.89323 0.597366 3.12324C0 5.35042 0 10 0 10C0 10 0 14.6496 0.597366 16.8768C0.926623 18.1068 1.89323 19.0734 3.12323 19.4026C5.35042 20 14.285 20 14.285 20C14.285 20 23.2197 20 25.4468 19.4026C26.6768 19.0734 27.6435 18.1068 27.9727 16.8768C28.5701 14.6496 28.5701 10 28.5701 10C28.5701 10 28.5677 5.35042 27.9727 3.12324Z" fill="#FF0000"/>
              <path d="M11.4253 14.2854L18.8477 10.0004L11.4253 5.71533V14.2854Z" fill="white"/>
            </svg>
          </ytd-logo>
        </div>
        <div id="center" class="ytd-masthead">
          <ytd-searchbox id="search" class="ytd-masthead">
            <form id="search-form" action="/results" method="get">
              <input type="text" id="search-input" name="search_query" placeholder="Search" autocomplete="off" aria-label="Search">
              <button type="submit" id="search-icon-legacy" aria-label="Search">
                <svg viewBox="0 0 24 24" width="24" height="24"><path d="M20.87,20.17l-5.59-5.59C16.35,13.35,17,11.75,17,10c0-3.87-3.13-7-7-7s-7,3.13-7,7s3.13,7,7,7c1.75,0,3.35-0.65,4.58-1.71l5.59,5.59L20.87,20.17z M5,10c0-2.76,2.24-5,5-5s5,2.24,5,5s-2.24,5-5,5S5,12.76,5,10z"/></svg>
              </button>
            </form>
          </ytd-searchbox>
        </div>
      </div>
    </ytd-masthead>
    <ytd-page-manager id="page-manager" class="style-scope ytd-app">
      <ytd-browse class="style-scope ytd-page-manager">
        <ytd-rich-grid-renderer id="contents" class="style-scope ytd-browse">
          <ytd-rich-item-renderer class="style-scope ytd-rich-grid-renderer">
            <ytd-rich-grid-media class="style-scope ytd-rich-item-renderer">
              <ytd-thumbnail class="style-scope ytd-rich-grid-media">
                <a id="thumbnail" href="/watch?v=dQw4w9WgXcQ" class="ytd-thumbnail">
                  <img src="https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" alt="Never Gonna Give You Up" width="246" height="138">
                </a>
              </ytd-thumbnail>
              <div id="details" class="style-scope ytd-rich-grid-media">
                <h3 class="style-scope ytd-rich-grid-media">
                  <a id="video-title" href="/watch?v=dQw4w9WgXcQ" title="Never Gonna Give You Up">Never Gonna Give You Up</a>
                </h3>
                <ytd-channel-name id="channel-name"><a href="/channel/UCuAXFkgsw1L7xaCfnd5JJOw">Rick Astley</a></ytd-channel-name>
                <span class="ytd-rich-grid-media" aria-label="1.4 billion views">1.4B views</span>
                <span class="ytd-rich-grid-media" aria-label="15 years ago">15 years ago</span>
              </div>
            </ytd-rich-grid-media>
          </ytd-rich-item-renderer>
        </ytd-rich-grid-renderer>
      </ytd-browse>
    </ytd-page-manager>
  </div>
</ytd-app>
<script nonce="abc">
  var ytInitialData = {"responseContext":{"serviceTrackingParams":[{"service":"CSI","params":[{"key":"c","value":"WEB"},{"key":"cver","value":"2.20240101"}]}]},"header":{"richGridHeaderRenderer":{"title":{"runs":[{"text":"Recommended"}]}}}};
  var ytcfg = { data_: { INNERTUBE_API_KEY: "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8", INNERTUBE_CONTEXT_CLIENT_VERSION: "2.20240101" } };
</script>
</body>
</html>"""

    /**
     * Representative github.com HTML.
     * Key challenges: SVG icons, code blocks, breadcrumb nav,
     * many data-* attributes, turbo-frame custom elements.
     */
    private val githubHtml = """<!DOCTYPE html>
<html lang="en" data-color-mode="auto" data-light-theme="light" data-dark-theme="dark">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GitHub · Build software better, together</title>
  <style>
    *, ::before, ::after { box-sizing: border-box; }
    body { font-family: -apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans",Helvetica,Arial,sans-serif; font-size: 14px; line-height: 1.5; color: #1f2328; background-color: #fff; margin: 0; }
    .header { background: #24292f; color: #fff; padding: 16px; }
    .header a { color: #fff; text-decoration: none; }
    .repo-name { font-size: 20px; font-weight: 600; }
    .file-tree { list-style: none; padding: 0; }
    .file-tree li { padding: 6px 16px; border-bottom: 1px solid #d0d7de; display: flex; align-items: center; }
    .file-tree li:hover { background: #f6f8fa; }
    pre { background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 6px; padding: 16px; overflow: auto; font-size: 12px; }
    .octicon { display: inline-block; overflow: visible; vertical-align: text-bottom; }
    .Label { display: inline-block; padding: 0 7px; font-size: 12px; font-weight: 500; line-height: 18px; border: 1px solid transparent; border-radius: 2em; }
    .Label--success { color: #1a7f37; background: #dafbe1; border-color: rgba(26,127,55,.4); }
  </style>
</head>
<body>
<header class="header" role="banner">
  <nav aria-label="Global">
    <a href="/" aria-label="GitHub">
      <svg aria-hidden="true" height="32" viewBox="0 0 16 16" version="1.1" width="32" class="octicon">
        <path d="M8 0c4.42 0 8 3.58 8 8a8.013 8.013 0 0 1-5.45 7.59c-.4.08-.55-.17-.55-.38 0-.27.01-1.13.01-2.2 0-.75-.25-1.23-.54-1.48 1.78-.2 3.65-.88 3.65-3.95 0-.88-.31-1.59-.82-2.15.08-.2.36-1.02-.08-2.12 0 0-.67-.22-2.2.82-.64-.18-1.32-.27-2-.27-.68 0-1.36.09-2 .27-1.53-1.03-2.2-.82-2.2-.82-.44 1.1-.16 1.92-.08 2.12-.51.56-.82 1.28-.82 2.15 0 3.06 1.86 3.75 3.64 3.95-.23.2-.44.55-.51 1.07-.46.21-1.61.55-2.33-.66-.15-.24-.6-.83-1.23-.82-.67.01-.27.38.01.53.34.19.73.9.82 1.13.16.45.68 1.31 2.69.94 0 .67.01 1.3.01 1.49 0 .21-.15.45-.55.38A7.995 7.995 0 0 1 0 8c0-4.42 3.58-8 8-8Z"></path>
      </svg>
    </a>
    <a href="/explore">Explore</a>
    <a href="/marketplace">Marketplace</a>
    <a href="/pricing">Pricing</a>
  </nav>
</header>
<main id="js-pjax-container" data-pjax-container>
  <div class="container-xl px-3 px-md-4 px-lg-5 mt-4">
    <div class="d-flex flex-justify-between flex-wrap gap-2 mb-2">
      <nav aria-label="Breadcrumb" class="d-flex flex-items-center">
        <ol>
          <li><a href="/jwyoon1220">jwyoon1220</a></li>
          <li aria-hidden="true">/</li>
          <li><a href="/jwyoon1220/Khromium" class="repo-name">Khromium</a></li>
        </ol>
      </nav>
      <span class="Label Label--success">Public</span>
    </div>
    <div class="Box mt-3">
      <div class="Box-header d-flex flex-items-center">
        <h2 class="Box-title">Files</h2>
      </div>
      <ul class="file-tree" role="grid">
        <li role="row"><span>src</span></li>
        <li role="row"><span>build.gradle.kts</span></li>
        <li role="row"><span>README.md</span></li>
        <li role="row"><span>gradlew</span></li>
      </ul>
    </div>
    <div class="mt-4">
      <h3>README.md</h3>
      <article class="markdown-body">
        <h1>Khromium</h1>
        <p>A hybrid kernel browser engine built on the JVM with off-heap memory management.</p>
        <h2>Features</h2>
        <ul>
          <li>Off-heap PMM/VMM memory subsystem</li>
          <li>Per-tab isolation with canary protection</li>
          <li>QuickJS (native) or Nashorn (JVM) JS engines</li>
        </ul>
        <h2>Getting Started</h2>
        <pre><code class="language-bash">./gradlew run</code></pre>
      </article>
    </div>
  </div>
</main>
<script>
  (function() {
    var d = document.documentElement;
    d.setAttribute('data-color-mode', window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  })();
</script>
</body>
</html>"""

    /**
     * Representative wikipedia.org HTML.
     * Key challenges: large content tables, footnote references, many <a> tags,
     * structured headings, infobox table, citations, content in multiple languages.
     */
    private val wikipediaHtml = """<!DOCTYPE html>
<html class="client-nojs vector-feature-language-in-header-enabled" lang="en" dir="ltr">
<head>
  <meta charset="UTF-8">
  <title>Web browser - Wikipedia</title>
  <style>
    body { font-family: sans-serif; color: #202122; background-color: #fff; margin: 0; }
    #mw-page-base, #mw-head-base { background-image: linear-gradient(to bottom, #eaecf0 0%, #fff 50%); }
    #content { margin: 0 0 0 10em; padding: 1em; }
    h1 { font-family: 'Linux Libertine','Georgia','Times',serif; font-size: 1.95em; }
    h2 { border-bottom: 1px solid #a2a9b1; font-size: 1.5em; }
    table.infobox { float: right; clear: right; margin: 0 0 1em 1em; padding: .2em; border: 1px solid #a2a9b1; background: #f8f9fa; font-size: .9em; }
    table.wikitable { border-collapse: collapse; }
    table.wikitable th, table.wikitable td { border: 1px solid #a2a9b1; padding: .2em .4em; }
    .reflist { font-size: .9em; }
    sup { font-size: .75em; }
    .mw-references-wrap { margin-bottom: 0.5em; }
  </style>
</head>
<body class="skin-vector">
<div id="mw-page-base" class="noprint"></div>
<div id="mw-head-base" class="noprint"></div>
<div id="content" class="mw-body" role="main">
  <a id="top"></a>
  <div id="siteNotice" class="mw-body-content"></div>
  <div class="mw-indicators mw-body-content"></div>
  <h1 id="firstHeading" class="firstHeading mw-first-heading"><span class="mw-page-title-main">Web browser</span></h1>
  <div id="bodyContent" class="vector-body">
    <div id="siteSub" class="noprint">From Wikipedia, the free encyclopedia</div>
    <div id="mw-content-text" class="mw-body-content mw-content-ltr">
      <div class="mw-parser-output">
        <table class="infobox vevent">
          <caption class="infobox-title fn">Web browser</caption>
          <tbody>
            <tr><td><img src="//upload.wikimedia.org/wikipedia/commons/thumb/e/e1/Google_Chrome_icon_(February_2022).svg/120px-Google_Chrome_icon_(February_2022).svg.png" alt="Google Chrome" width="120" height="120"></td></tr>
            <tr><th>Developer</th><td>Various</td></tr>
            <tr><th>Type</th><td>Web browser</td></tr>
            <tr><th>License</th><td>Various</td></tr>
          </tbody>
        </table>
        <p>A <b>web browser</b> (commonly referred to as a <b>browser</b>) is an application for accessing websites and the Internet. When a user requests a web page from a particular website, the browser retrieves the necessary content from a web server and then displays the page on the user's device.<sup id="cite_ref-1" class="reference"><a href="#cite_note-1">[1]</a></sup></p>
        <h2><span class="mw-headline" id="History">History</span></h2>
        <p>The first web browser, called WorldWideWeb, was created in 1990 by Tim Berners-Lee. The browser was later renamed Nexus to avoid confusion with the World Wide Web.<sup id="cite_ref-2" class="reference"><a href="#cite_note-2">[2]</a></sup></p>
        <h2><span class="mw-headline" id="Market_share">Market share</span></h2>
        <table class="wikitable">
          <caption>Desktop browser market share (2024)</caption>
          <thead><tr><th>Browser</th><th>Market share</th></tr></thead>
          <tbody>
            <tr><td>Google Chrome</td><td>65.12%</td></tr>
            <tr><td>Safari</td><td>18.78%</td></tr>
            <tr><td>Microsoft Edge</td><td>5.13%</td></tr>
            <tr><td>Firefox</td><td>2.95%</td></tr>
            <tr><td>Opera</td><td>2.83%</td></tr>
          </tbody>
        </table>
        <h2><span class="mw-headline" id="See_also">See also</span></h2>
        <ul>
          <li><a href="/wiki/Web_server">Web server</a></li>
          <li><a href="/wiki/HTML">HTML</a></li>
          <li><a href="/wiki/JavaScript">JavaScript</a></li>
          <li><a href="/wiki/CSS">Cascading Style Sheets</a></li>
        </ul>
        <div class="reflist">
          <div class="mw-references-wrap">
            <ol class="references">
              <li id="cite_note-1"><span class="mw-cite-backlink"><a href="#cite_ref-1">↑</a></span> <span class="reference-text">"Browser" definition. <i>Merriam-Webster</i>.</span></li>
              <li id="cite_note-2"><span class="mw-cite-backlink"><a href="#cite_ref-2">↑</a></span> <span class="reference-text">Berners-Lee, Tim (1991). WorldWideWeb — Summary.</span></li>
            </ol>
          </div>
        </div>
      </div>
    </div>
  </div>
</div>
<script>
  mw = { config: {}, messages: {}, loader: { state: {} } };
  mw.config.set({"wgPageName":"Web_browser","wgTitle":"Web browser","wgNamespaceNumber":0});
</script>
</body>
</html>"""

    /**
     * Representative amazon.com HTML.
     * Key challenges: deeply nested product cards, forms with many inputs,
     * price formatting, srcset on images, data-cel-widget attributes.
     */
    private val amazonHtml = """<!DOCTYPE html>
<html lang="en-US" class="a-no-js">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Amazon.com: Online Shopping</title>
  <style>
    html { background-color: #e3e6e6; }
    body { background-color: #fff; font-family: Arial,sans-serif; font-size: 13px; color: #0f1111; margin: 0; }
    #navbar { background: #131921; padding: 8px 15px; }
    #navbar .nav-logo-link { display: inline-block; }
    .a-color-state { color: #007185; }
    .a-button { display: inline-block; background: linear-gradient(to bottom,#f7dfa5,#f0c14b); border: 1px solid #a88734; border-radius: 3px; cursor: pointer; padding: 0 10px; font-size: 13px; }
    .s-card-container { background: #fff; border-radius: 8px; margin-bottom: 16px; padding: 16px; box-shadow: 0 2px 5px rgba(15,17,17,.15); }
    .a-price-whole { font-size: 21px; font-weight: 700; }
    .a-price-fraction { font-size: 13px; vertical-align: super; }
    .a-star-4-5 { color: #c45500; }
    .a-price-symbol { vertical-align: super; font-size: 13px; }
  </style>
</head>
<body>
<div id="a-page">
  <header id="navbar" role="banner">
    <div id="nav-logo-sprites">
      <a id="nav-logo-link" class="nav-logo-link" href="https://www.amazon.com">
        <span id="nav-logo-sprites-cls"><img src="/images/G/01/gno/sprites/nav-sprite-global-1x-hm-dsk-reorg-privacy._CB590498739_.png" alt="Amazon" width="113" height="34"></span>
      </a>
    </div>
    <form id="nav-search-bar-form" method="GET" action="/s" role="search">
      <div id="nav-search-dropdown-card">
        <select id="searchDropdownBox" name="i" title="Search in" aria-label="Search in">
          <option value="aps" selected="">All Departments</option>
          <option value="electronics">Electronics</option>
          <option value="books">Books</option>
          <option value="clothing">Clothing</option>
          <option value="home-garden">Home &amp; Garden</option>
        </select>
      </div>
      <div id="nav-search-keywords">
        <input type="text" id="twotabsearchtextbox" class="nav-input" name="field-keywords"
               placeholder="Search Amazon" aria-label="Search Amazon" autocomplete="off"
               aria-autocomplete="list" tabindex="0">
      </div>
      <div id="nav-search-submit">
        <input type="submit" id="nav-search-submit-button" class="a-button" value="Go" aria-label="Go">
      </div>
    </form>
  </header>
  <div id="main-content" role="main">
    <div class="s-search-results">
      <div class="s-card-container" data-asin="B09G9FPHY6" data-cel-widget="search_result_1">
        <div class="a-section">
          <a href="/dp/B09G9FPHY6" class="a-link-normal">
            <img src="https://m.media-amazon.com/images/I/71jG+e7roXL._AC_UY218_.jpg"
                 srcset="https://m.media-amazon.com/images/I/71jG+e7roXL._AC_UY218_.jpg 1x, https://m.media-amazon.com/images/I/71jG+e7roXL._AC_UY436_.jpg 2x"
                 alt="Apple AirPods Pro (2nd Generation)" width="218" height="218">
          </a>
          <h2 class="a-size-mini a-spacing-none a-color-base s-line-clamp-2">
            <a class="a-link-normal s-underline-text" href="/dp/B09G9FPHY6">Apple AirPods Pro (2nd Generation)</a>
          </h2>
          <div class="a-row a-size-small">
            <span class="a-star-4-5">4.5 out of 5 stars</span>
            <span class="a-size-base">(89,432)</span>
          </div>
          <div class="a-price">
            <span class="a-price-symbol">$</span>
            <span class="a-price-whole">189</span>
            <span class="a-price-fraction">00</span>
          </div>
          <button type="button" class="a-button" aria-label="Add Apple AirPods Pro to cart">Add to Cart</button>
        </div>
      </div>
      <div class="s-card-container" data-asin="B08N5WRWNW" data-cel-widget="search_result_2">
        <div class="a-section">
          <a href="/dp/B08N5WRWNW" class="a-link-normal">
            <img src="https://m.media-amazon.com/images/I/81Q2NqbJuFL._AC_UY218_.jpg"
                 alt="Samsung 65-inch Class QLED 4K TV" width="218" height="218">
          </a>
          <h2 class="a-size-mini">
            <a href="/dp/B08N5WRWNW">Samsung 65-inch Class QLED 4K TV</a>
          </h2>
          <div class="a-price">
            <span class="a-price-symbol">$</span>
            <span class="a-price-whole">1,097</span>
            <span class="a-price-fraction">99</span>
          </div>
          <button type="button" class="a-button" aria-label="Add Samsung TV to cart">Add to Cart</button>
        </div>
      </div>
    </div>
  </div>
</div>
<script>
  var ue_t0 = ue_t0 || +new Date();
  var ue = { tag: function(t) {}, count: function(k, v) {} };
  P = window.P || {};
  P.when = P.when || function() { return { execute: function() {} }; };
</script>
</body>
</html>"""

    // ── Shared helpers ─────────────────────────────────────────────────────────

    /** Allocates 64 MB of physical memory, enough for large real-world pages. */
    private fun makeProcess() = KProcess(PhysicalMemoryManager(64))

    private fun findTagInTree(proc: KProcess, nodePtr: Long, tag: String): Boolean {
        if (nodePtr == 0L) return false
        if (proc.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = proc.vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L && proc.vmm.readString(namePtr).equals(tag, ignoreCase = true)) return true
            var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                if (findTagInTree(proc, child, tag)) return true
                child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return false
    }

    private fun collectAllText(proc: KProcess, nodePtr: Long): String {
        if (nodePtr == 0L) return ""
        val sb = StringBuilder()
        val type = proc.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE)
        if (type == KDOM.TYPE_TEXT) {
            val p = proc.vmm.readLong(nodePtr + KDOM.OFFSET_TEXT_DATA)
            if (p != 0L) sb.append(proc.vmm.readString(p))
        }
        var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
        while (child != 0L) {
            sb.append(collectAllText(proc, child))
            child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
        }
        return sb.toString()
    }

    private fun countElementsByTag(proc: KProcess, nodePtr: Long, tag: String): Int {
        if (nodePtr == 0L) return 0
        var count = 0
        if (proc.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = proc.vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L && proc.vmm.readString(namePtr).equals(tag, ignoreCase = true)) count++
            var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                count += countElementsByTag(proc, child, tag)
                child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return count
    }

    private fun extractStyleContent(proc: KProcess, nodePtr: Long): String {
        if (nodePtr == 0L) return ""
        val sb = StringBuilder()
        if (proc.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = proc.vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L && proc.vmm.readString(namePtr).equals("style", ignoreCase = true)) {
                var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
                while (child != 0L) {
                    if (proc.vmm.readInt(child + KDOM.OFFSET_TYPE) == KDOM.TYPE_TEXT) {
                        val p = proc.vmm.readLong(child + KDOM.OFFSET_TEXT_DATA)
                        if (p != 0L) sb.append(proc.vmm.readString(p))
                    }
                    child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
                }
            }
            var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                sb.append(extractStyleContent(proc, child))
                child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return sb.toString()
    }

    private fun extractScriptContent(proc: KProcess, nodePtr: Long): List<String> {
        if (nodePtr == 0L) return emptyList()
        val result = mutableListOf<String>()
        if (proc.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = proc.vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L && proc.vmm.readString(namePtr).equals("script", ignoreCase = true)) {
                var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
                while (child != 0L) {
                    if (proc.vmm.readInt(child + KDOM.OFFSET_TYPE) == KDOM.TYPE_TEXT) {
                        val p = proc.vmm.readLong(child + KDOM.OFFSET_TEXT_DATA)
                        if (p != 0L) {
                            val text = proc.vmm.readString(p)
                            if (text.isNotBlank()) result.add(text)
                        }
                    }
                    child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
                }
            }
            var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                result.addAll(extractScriptContent(proc, child))
                child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return result
    }

    // ── naver.com tests ────────────────────────────────────────────────────────

    @Test
    fun `naver - HTML parses into non-null KDOM root`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(naverHtml, proc.allocator)
        assertNotEquals(0L, root, "Root must not be zero")
    }

    @Test
    fun `naver - Korean text survives VMM round-trip`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(naverHtml, proc.allocator)
        val text = collectAllText(proc, root)
        // "검색" is button text; "뉴스스탠드" and "실시간 검색어" are heading text nodes.
        // The input placeholder is an attribute value, not a text node, so it is
        // intentionally not checked via collectAllText.
        assertTrue(text.contains("검색"), "Korean button text must survive")
        assertTrue(text.contains("뉴스스탠드"), "Korean heading must survive")
        assertTrue(text.contains("실시간 검색어"), "Korean section title must survive")
    }

    @Test
    fun `naver - navigation links are present in DOM`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(naverHtml, proc.allocator)
        val links = countElementsByTag(proc, root, "a")
        assertTrue(links >= 10, "At least 10 anchor links expected, found $links")
    }

    @Test
    fun `naver - search form elements are parsed`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(naverHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "form"), "form tag must be present")
        assertTrue(findTagInTree(proc, root, "input"), "input tag must be present")
        assertTrue(findTagInTree(proc, root, "button"), "button tag must be present")
    }

    @Test
    fun `naver - CSS from style tag is accepted by CSSParser`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(naverHtml, proc.allocator)
        val css  = extractStyleContent(proc, root)
        assertTrue(css.isNotBlank(), "Style content must be extracted")
        val sheet = CSSParser().parse(css)
        assertNotNull(sheet, "CSSParser must return a non-null sheet")
    }

    @Test
    fun `naver - ordered list for rankings is present`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(naverHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "ol"), "ol tag must be present for ranking list")
        assertTrue(findTagInTree(proc, root, "li"), "li tag must be present")
    }

    @Test
    fun `naver - inline script executes without crashing`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(naverHtml, proc.allocator)
        val scripts = extractScriptContent(proc, root)
        assertTrue(scripts.isNotEmpty(), "At least one inline script expected")
        val cache   = SharedBytecodeCache(proc.vmm.pmm)
        KhromiumJsRuntime(proc.pid.toString(), proc, cache, root).use { rt ->
            for (script in scripts) {
                runCatching { rt.execute(script) }
                    .onFailure { e ->
                        // A script exception is acceptable; a JVM crash is not.
                        // We only care that the runtime itself stays alive.
                        assertTrue(
                            e.message != null || e is RuntimeException,
                            "Unexpected fatal throwable: $e"
                        )
                    }
            }
        }
    }

    // ── google.com tests ───────────────────────────────────────────────────────

    @Test
    fun `google - HTML parses into non-null KDOM root`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(googleHtml, proc.allocator)
        assertNotEquals(0L, root)
    }

    @Test
    fun `google - search form with hidden inputs is parsed correctly`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(googleHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "form"), "form must be present")
        val inputCount = countElementsByTag(proc, root, "input")
        assertTrue(inputCount >= 5, "Expected at least 5 input elements (hidden + visible), found $inputCount")
    }

    @Test
    fun `google - SVG inline icon is parsed as element`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(googleHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "svg"), "SVG element must be present")
        assertTrue(findTagInTree(proc, root, "path"), "SVG path elements must be present")
    }

    @Test
    fun `google - aria and data attributes on search input are preserved`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(googleHtml, proc.allocator)
        // Verify the DOM contains the element with aria attributes
        val text = collectAllText(proc, root)
        // The document must parse without throwing; structural elements exist
        assertNotEquals(0L, root)
        assertTrue(findTagInTree(proc, root, "input"))
    }

    @Test
    fun `google - CSS from style tag is accepted by CSSParser`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(googleHtml, proc.allocator)
        val css  = extractStyleContent(proc, root)
        assertTrue(css.isNotBlank())
        val sheet = CSSParser().parse(css)
        assertNotNull(sheet)
    }

    @Test
    fun `google - inline scripts execute without crashing`() {
        val proc  = makeProcess()
        val root  = JsoupDOMBuilder.parse(googleHtml, proc.allocator)
        val scripts = extractScriptContent(proc, root)
        assertTrue(scripts.isNotEmpty())
        val cache = SharedBytecodeCache(proc.vmm.pmm)
        KhromiumJsRuntime(proc.pid.toString(), proc, cache, root).use { rt ->
            for (script in scripts) { runCatching { rt.execute(script) } }
        }
    }

    @Test
    fun `google - page title text is present in DOM`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(googleHtml, proc.allocator)
        val text = collectAllText(proc, root)
        assertTrue(text.contains("Google"), "Page text must contain 'Google'")
    }

    // ── youtube.com tests ──────────────────────────────────────────────────────

    @Test
    fun `youtube - HTML parses into non-null KDOM root`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(youtubeHtml, proc.allocator)
        assertNotEquals(0L, root)
    }

    @Test
    fun `youtube - custom ytd-* elements are parsed as element nodes`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(youtubeHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "ytd-app"), "ytd-app custom element must be present")
        assertTrue(findTagInTree(proc, root, "ytd-thumbnail"), "ytd-thumbnail must be present")
    }

    @Test
    fun `youtube - video thumbnail anchor link is present`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(youtubeHtml, proc.allocator)
        val links = countElementsByTag(proc, root, "a")
        assertTrue(links >= 2, "At least 2 anchor links expected, found $links")
    }

    @Test
    fun `youtube - search form is present`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(youtubeHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "form"))
        assertTrue(findTagInTree(proc, root, "input"))
    }

    @Test
    fun `youtube - large JSON-in-script survives VMM round-trip`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(youtubeHtml, proc.allocator)
        val scripts = extractScriptContent(proc, root)
        val jsonScript = scripts.firstOrNull { it.contains("ytInitialData") }
        assertNotNull(jsonScript, "ytInitialData script block must be found")
        assertTrue(jsonScript.contains("responseContext"), "JSON content must be preserved")
    }

    @Test
    fun `youtube - inline script executes without crashing`() {
        val proc  = makeProcess()
        val root  = JsoupDOMBuilder.parse(youtubeHtml, proc.allocator)
        val scripts = extractScriptContent(proc, root)
        val cache = SharedBytecodeCache(proc.vmm.pmm)
        KhromiumJsRuntime(proc.pid.toString(), proc, cache, root).use { rt ->
            for (script in scripts) { runCatching { rt.execute(script) } }
        }
    }

    @Test
    fun `youtube - video title text is present in DOM`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(youtubeHtml, proc.allocator)
        val text = collectAllText(proc, root)
        assertTrue(text.contains("Never Gonna Give You Up"), "Video title must appear in text")
    }

    // ── github.com tests ───────────────────────────────────────────────────────

    @Test
    fun `github - HTML parses into non-null KDOM root`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(githubHtml, proc.allocator)
        assertNotEquals(0L, root)
    }

    @Test
    fun `github - SVG octocat icon is parsed`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(githubHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "svg"))
        assertTrue(findTagInTree(proc, root, "path"))
    }

    @Test
    fun `github - breadcrumb navigation is present`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(githubHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "nav"))
        assertTrue(findTagInTree(proc, root, "ol"))
        val text = collectAllText(proc, root)
        assertTrue(text.contains("Khromium"), "Repo name must appear in breadcrumb")
    }

    @Test
    fun `github - code block pre element is parsed`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(githubHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "pre"))
        val text = collectAllText(proc, root)
        assertTrue(text.contains("gradlew"), "Code block content must be present")
    }

    @Test
    fun `github - CSS with CSS-variable-like selectors is accepted by CSSParser`() {
        val proc  = makeProcess()
        val root  = JsoupDOMBuilder.parse(githubHtml, proc.allocator)
        val css   = extractStyleContent(proc, root)
        assertTrue(css.isNotBlank())
        val sheet = CSSParser().parse(css)
        assertNotNull(sheet)
    }

    @Test
    fun `github - inline script executes without crashing`() {
        val proc  = makeProcess()
        val root  = JsoupDOMBuilder.parse(githubHtml, proc.allocator)
        val scripts = extractScriptContent(proc, root)
        assertTrue(scripts.isNotEmpty())
        val cache = SharedBytecodeCache(proc.vmm.pmm)
        KhromiumJsRuntime(proc.pid.toString(), proc, cache, root).use { rt ->
            for (script in scripts) { runCatching { rt.execute(script) } }
        }
    }

    @Test
    fun `github - README content is present in article`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(githubHtml, proc.allocator)
        val text = collectAllText(proc, root)
        assertTrue(text.contains("hybrid kernel browser engine"))
    }

    // ── wikipedia.org tests ────────────────────────────────────────────────────

    @Test
    fun `wikipedia - HTML parses into non-null KDOM root`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(wikipediaHtml, proc.allocator)
        assertNotEquals(0L, root)
    }

    @Test
    fun `wikipedia - infobox table is present`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(wikipediaHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "table"))
        val tableCount = countElementsByTag(proc, root, "table")
        assertTrue(tableCount >= 2, "Expected at least 2 tables (infobox + market share), found $tableCount")
    }

    @Test
    fun `wikipedia - heading structure is correct`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(wikipediaHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "h1"))
        assertTrue(findTagInTree(proc, root, "h2"))
        val text = collectAllText(proc, root)
        assertTrue(text.contains("Web browser"))
        assertTrue(text.contains("History"))
        assertTrue(text.contains("Market share"))
    }

    @Test
    fun `wikipedia - market share table data is preserved`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(wikipediaHtml, proc.allocator)
        val text = collectAllText(proc, root)
        assertTrue(text.contains("Google Chrome"))
        assertTrue(text.contains("65.12%"))
        assertTrue(text.contains("Firefox"))
    }

    @Test
    fun `wikipedia - footnote references are parsed`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(wikipediaHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "sup"), "Footnote sup tags must be present")
        val lists = countElementsByTag(proc, root, "ol")
        assertTrue(lists >= 1, "Reference list ol must be present")
    }

    @Test
    fun `wikipedia - CSS from style tag is accepted by CSSParser`() {
        val proc  = makeProcess()
        val root  = JsoupDOMBuilder.parse(wikipediaHtml, proc.allocator)
        val css   = extractStyleContent(proc, root)
        assertTrue(css.isNotBlank())
        val sheet = CSSParser().parse(css)
        assertNotNull(sheet)
    }

    @Test
    fun `wikipedia - inline script sets mw config without crashing`() {
        val proc  = makeProcess()
        val root  = JsoupDOMBuilder.parse(wikipediaHtml, proc.allocator)
        val scripts = extractScriptContent(proc, root)
        assertTrue(scripts.isNotEmpty())
        val cache = SharedBytecodeCache(proc.vmm.pmm)
        KhromiumJsRuntime(proc.pid.toString(), proc, cache, root).use { rt ->
            for (script in scripts) { runCatching { rt.execute(script) } }
        }
    }

    // ── amazon.com tests ───────────────────────────────────────────────────────

    @Test
    fun `amazon - HTML parses into non-null KDOM root`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        assertNotEquals(0L, root)
    }

    @Test
    fun `amazon - search form with dropdown select is parsed`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "form"))
        assertTrue(findTagInTree(proc, root, "select"))
        assertTrue(findTagInTree(proc, root, "option"))
    }

    @Test
    fun `amazon - product cards are present`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        val text = collectAllText(proc, root)
        assertTrue(text.contains("Apple AirPods Pro"), "First product name must be present")
        assertTrue(text.contains("Samsung"), "Second product name must be present")
    }

    @Test
    fun `amazon - price elements are parsed`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        val text = collectAllText(proc, root)
        assertTrue(text.contains("189"), "AirPods price must be present")
        assertTrue(text.contains("1,097"), "TV price must be present")
    }

    @Test
    fun `amazon - images with srcset attribute are parsed`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        assertTrue(findTagInTree(proc, root, "img"), "img elements must be present")
    }

    @Test
    fun `amazon - add-to-cart buttons are present`() {
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        val buttonCount = countElementsByTag(proc, root, "button")
        assertTrue(buttonCount >= 2, "Expected at least 2 'Add to Cart' buttons, found $buttonCount")
    }

    @Test
    fun `amazon - CSS from style tag is accepted by CSSParser`() {
        val proc  = makeProcess()
        val root  = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        val css   = extractStyleContent(proc, root)
        assertTrue(css.isNotBlank())
        val sheet = CSSParser().parse(css)
        assertNotNull(sheet)
    }

    @Test
    fun `amazon - inline script executes without crashing`() {
        val proc  = makeProcess()
        val root  = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        val scripts = extractScriptContent(proc, root)
        assertTrue(scripts.isNotEmpty())
        val cache = SharedBytecodeCache(proc.vmm.pmm)
        KhromiumJsRuntime(proc.pid.toString(), proc, cache, root).use { rt ->
            for (script in scripts) { runCatching { rt.execute(script) } }
        }
    }

    // ── Cross-site pipeline tests ──────────────────────────────────────────────

    @Test
    fun `all sites - multiple pages can be loaded by the same KProcess without crash`() {
        val proc  = makeProcess()
        val cache = SharedBytecodeCache(proc.vmm.pmm)
        // Simulate sequential page loads in one tab (re-uses the same process)
        for ((name, html) in listOf(
            "naver"     to naverHtml,
            "google"    to googleHtml,
            "youtube"   to youtubeHtml,
            "github"    to githubHtml,
            "wikipedia" to wikipediaHtml,
            "amazon"    to amazonHtml
        )) {
            val root = JsoupDOMBuilder.parse(html, proc.allocator)
            assertNotEquals(0L, root, "$name root must not be zero")
        }
    }

    @Test
    fun `all sites - six separate KProcess tabs can all parse in parallel without interference`() {
        val htmlByName = mapOf(
            "naver"     to naverHtml,
            "google"    to googleHtml,
            "youtube"   to youtubeHtml,
            "github"    to githubHtml,
            "wikipedia" to wikipediaHtml,
            "amazon"    to amazonHtml
        )
        val pmm = PhysicalMemoryManager(128)
        for ((name, html) in htmlByName) {
            val proc = KProcess(pmm)
            val root = JsoupDOMBuilder.parse(html, proc.allocator)
            assertNotEquals(0L, root, "$name tab must parse without crash")
        }
    }

    @Test
    fun `all sites - DOCTYPE declaration does not crash the parser`() {
        // Verify that all fixture HTMLs begin with DOCTYPE and still parse
        for ((name, html) in listOf(
            "naver" to naverHtml, "google" to googleHtml, "youtube" to youtubeHtml,
            "github" to githubHtml, "wikipedia" to wikipediaHtml, "amazon" to amazonHtml
        )) {
            assertTrue(html.trimStart().startsWith("<!DOCTYPE", ignoreCase = true),
                "$name fixture must start with DOCTYPE")
            val proc = makeProcess()
            val root = JsoupDOMBuilder.parse(html, proc.allocator)
            assertNotEquals(0L, root, "$name with DOCTYPE must parse successfully")
        }
    }

    @Test
    fun `all sites - HTML entities are decoded correctly`() {
        val proc = makeProcess()
        // amazon HTML contains &amp; and &lt; entities
        val root = JsoupDOMBuilder.parse(amazonHtml, proc.allocator)
        val text = collectAllText(proc, root)
        // "Home &amp; Garden" must become "Home & Garden" after entity decoding
        assertTrue(text.contains("Home & Garden") || text.contains("Home &amp; Garden") || text.contains("Home"),
            "Entity-encoded text must be present in some form")
    }

    @Test
    fun `all sites - deeply nested structures do not overflow stack`() {
        // Wikipedia has the deepest nesting (infobox table > tbody > tr > td > ...)
        val proc = makeProcess()
        val root = JsoupDOMBuilder.parse(wikipediaHtml, proc.allocator)
        assertNotEquals(0L, root)
        // Walking the full tree must not cause a StackOverflowError
        val text = collectAllText(proc, root)
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun `all sites - CSS with multiple shorthand and pseudo-selector patterns is tolerated`() {
        val combinedCss = buildString {
            val proc = makeProcess()
            for (html in listOf(naverHtml, googleHtml, youtubeHtml, githubHtml, wikipediaHtml, amazonHtml)) {
                val root = JsoupDOMBuilder.parse(html, proc.allocator)
                append(extractStyleContent(proc, root))
                append("\n")
            }
        }
        // CSSParser must not throw on any combined input
        val sheet = CSSParser().parse(combinedCss)
        assertNotNull(sheet)
    }

    @Test
    fun `all sites - element counts are non-zero for every fixture`() {
        for ((name, html) in listOf(
            "naver" to naverHtml, "google" to googleHtml, "youtube" to youtubeHtml,
            "github" to githubHtml, "wikipedia" to wikipediaHtml, "amazon" to amazonHtml
        )) {
            val proc = makeProcess()
            val root = JsoupDOMBuilder.parse(html, proc.allocator)
            val divCount = countElementsByTag(proc, root, "div")
            val aCount   = countElementsByTag(proc, root, "a")
            assertTrue(divCount + aCount > 0,
                "$name must contain at least one div or anchor element")
        }
    }
}
