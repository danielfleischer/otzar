(ns rag.index.extract.html-test
  "Covers HTML extraction, with the line-fidelity property as the centrepiece:
  the extractor's whole claim is that it can keep the \"line\" locator honest,
  which is only true if the output has exactly the newlines the input had — no
  matter what was dropped in between."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [rag.index.extract.html :as html]
            [rag.index.segmenter :as segmenter])
  (:import [java.io File]))

(defn- html-file
  "Write `s` to a temp .html file and return it."
  ^File [s]
  (let [f (File/createTempFile "otzar-test" ".html")]
    (.deleteOnExit f)
    (spit f s)
    f))

(defn- extract [s]
  (html/extract (html-file s)))

(defn- lines [s]
  (vec (str/split-lines (or s ""))))

(deftest strips-tags
  (testing "markup goes, text stays"
    (is (= "Hello world"
           (str/trim (extract "<p class=\"lead\">Hello <b>world</b></p>"))))))

(deftest separates-adjacent-elements
  (testing "adjacent elements do not run their words together"
    (is (= "foo bar"
           (str/trim (str/replace (extract "<li>foo</li><li>bar</li>") #"\s+" " "))))))

(deftest drops-script-and-style
  (testing "script and style bodies are not prose and must not be indexed"
    (let [text (extract "<style>.a{color:red}</style><p>keep</p><script>var x = 1 < 2;</script>")]
      (is (str/includes? text "keep"))
      (is (not (str/includes? text "color")))
      (is (not (str/includes? text "var x"))))))

(deftest decodes-entities
  (testing "named, numeric and hex character references"
    (is (= "AT&T \"quoted\" — ©"
           (str/trim (extract "<p>AT&amp;T &quot;quoted&quot; &#8212; &#xA9;</p>"))))))

(deftest leaves-unknown-entities-alone
  (testing "an unrecognized reference is left as written rather than eaten"
    (is (str/includes? (extract "<p>&notareal; x</p>") "&notareal;"))))

(deftest bare-less-than-is-text
  (testing "'a < b' is prose, not a tag"
    (is (= "a < b" (str/trim (extract "<p>a < b</p>"))))))

(deftest attribute-containing-gt
  (testing "a '>' inside a quoted attribute does not end the tag early"
    (is (= "text" (str/trim (extract "<a title=\"a > b\" href='x>y'>text</a>"))))))

(deftest unterminated-tag
  (testing "markup that simply stops swallows the remainder rather than throwing"
    (is (= "before" (str/trim (extract "<p>before</p><div class=\"x"))))))

(deftest nil-when-no-text
  (testing "a page with nothing to index is skipped"
    (is (nil? (extract "")))
    (is (nil? (extract "<script>var x = 1;</script>")))
    (is (nil? (extract "<!-- just a comment -->")))))

;; ── Site chrome ─────────────────────────────────────────────────────────────

(deftest drops-chrome-bodies
  (testing "nav, header, footer and aside are furniture, not content"
    (let [text (extract (str "<nav><a href=\"/\">HOME</a><a href=\"/about\">ABOUT</a></nav>"
                             "<p>real content</p>"
                             "<aside>Related links</aside>"
                             "<footer>© 2021 | 202.236.9298</footer>"))]
      (is (str/includes? text "real content"))
      (doseq [gone ["HOME" "ABOUT" "Related links" "202.236.9298"]]
        (is (not (str/includes? text gone)) (str "should have dropped: " gone))))))

(deftest keeps-headings-inside-chrome
  (testing "an article's title block is a <header> too — the h1 must survive"
    (let [text (extract "<header><nav>MENU</nav><h1>The Real Title</h1></header><p>body</p>")]
      (is (str/includes? text "The Real Title"))
      (is (str/includes? text "body"))
      (is (not (str/includes? text "MENU"))))))

(deftest handles-nested-chrome
  (testing "a nav inside a header closes only its own depth"
    (let [text (extract "<header><nav>MENU</nav>banner text</header><p>content</p>")]
      (is (str/includes? text "content"))
      (is (not (str/includes? text "MENU")))
      (is (not (str/includes? text "banner text"))
          "text after a nested close is still inside the outer header"))))

(deftest self-closing-chrome-opens-nothing
  (testing "<nav/> must not swallow the rest of the document"
    (is (str/includes? (extract "<nav/><p>survives</p>") "survives"))))

(deftest stray-close-does-not-underflow
  (testing "an unmatched </nav> leaves depth at zero rather than going negative"
    (is (str/includes? (extract "</nav><p>survives</p>") "survives"))))

(deftest content-tags-are-not-chrome
  (testing "main, article and section hold the actual page"
    (let [text (extract "<main><article><section><p>keep me</p></section></article></main>")]
      (is (str/includes? text "keep me")))))

(deftest chrome-preserves-line-numbers
  (testing "dropping a chrome body still keeps every newline it spanned"
    (let [src (str "<p>one</p>\n<nav>\n  <a>HOME</a>\n  <a>ABOUT</a>\n</nav>\n<p>two</p>")]
      (is (= (count (lines src)) (count (lines (extract src)))))
      (is (str/includes? (nth (lines (extract src)) 5) "two")))))

;; ── Line fidelity ───────────────────────────────────────────────────────────
;; The reason HTML may keep the "line" locator: citations only mean something if
;; extracted line N is source line N.

(deftest preserves-line-count
  (testing "output has exactly as many lines as the source, whatever was dropped"
    (doseq [src ["<p>one</p>\n<p>two</p>\n<p>three</p>"
                 "<a\n  href=\"x\"\n  title=\"y\">link</a>"
                 "<!--\n  a comment\n  over lines\n-->text"
                 "<script>\nvar x = 1;\nvar y = 2;\n</script>\ntext"]]
      (is (= (count (lines src)) (count (lines (extract src))))
          (str "line count changed for: " (pr-str src))))))

(deftest text-stays-on-its-source-line
  (testing "a word lands on the line it was written on, past a multi-line tag"
    (let [src  (str "<html>\n"                 ; 1
                    "<body>\n"                 ; 2
                    "<!-- note\n"              ; 3
                    "     spanning lines -->\n" ; 4
                    "<a\n"                     ; 5
                    "   href=\"/x\">link</a>\n" ; 6
                    "<p>needle</p>\n")         ; 7
          out  (lines (extract src))]
      (is (str/includes? (nth out 5) "link"))
      (is (str/includes? (nth out 6) "needle")))))

(deftest locators-match-source-lines
  (testing "end to end: the segmenter numbers a sentence with its real source line"
    (let [src  (str "<html>\n<body>\n"
                    "<p>First sentence here.</p>\n"
                    "<p>Second sentence here.</p>\n")
          segs (segmenter/segment (html/extract (html-file src)) "line")
          by   (into {} (map (juxt :text :loc)) segs)]
      (is (= 3 (get by "First sentence here.")))
      (is (= 4 (get by "Second sentence here."))))))
