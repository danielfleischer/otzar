(ns rag.index.extract.html
  "HTML text extraction — one format in the rag.index.extractor registry.

  Until this existed, .html was claimed by the plain-text format and indexed as
  raw markup: every `<div class=\"…\">` was text to be embedded and matched, so a
  saved article was mostly noise and BM25 happily scored a hit on the word
  \"class\".

  Site furniture goes too: the bodies of <nav>, <header>, <footer> and <aside>
  are dropped (headings inside them survive — see `chrome-tags`), so a corpus of
  saved pages doesn't fill up with one near-identical chunk of nav labels per
  page. This only reaches pages written with those elements; an older layout of
  <div class=\"nav\"> is indistinguishable from content without the text-density
  heuristics of a readability pass, which this is not.

  Extraction is tag-stripping rather than parsing, and it keeps the file's line
  structure exactly: every newline in the source — including newlines inside a
  tag, a comment, or a <script> body — is preserved in the output, and nothing
  ever inserts one. That is what lets HTML keep the \"line\" locator honestly.
  The rule from the segmenter is that line numbers are faithful only while the
  extracted text lines up with the file's lines, so an extractor that reflowed
  the text (as the PDF one must) would have to cite something else instead. Here
  we can just not reflow, and `article.html:42` is then a line you can actually
  go look at. The cost is that a minified page is one enormous line — a true
  citation, if not a very useful one.

  What this deliberately is not: a parser. There is no DOM, no tag balancing, no
  understanding of block vs. inline (which would want to insert line breaks, and
  so can't happen here). Malformed markup degrades into slightly noisier text
  rather than an error. For a personal corpus of saved pages and exported notes
  that trade is worth one fewer dependency — the same call the project made with
  BreakIterator over OpenNLP, and PDFBox over Tika.

  Known limit: the bytes are read as UTF-8 regardless of any charset declared in
  a <meta> tag. A page saved in windows-1255 or latin-1 will come out mangled;
  it does not throw, so it lands in the index as junk text rather than as an
  error. Every modern export is UTF-8, so this is left until a real file trips
  on it."
  (:require [clojure.string :as str])
  (:import [java.nio.file Files]
           [java.nio.charset StandardCharsets]))

(def ^:private named-entities
  "The named character references worth decoding by hand. HTML defines ~2000 of
  them; these are the ones that actually turn up in prose, plus the five XML
  built-ins. Anything else is left as written — `&copy;` reads as `&copy;`, which
  is ugly but honest, and does not affect retrieval of the words around it."
  {"amp" "&", "lt" "<", "gt" ">", "quot" "\"", "apos" "'",
   "nbsp" " ", "ensp" " ", "emsp" " ", "thinsp" " ", "shy" "",
   "hellip" "…", "mdash" "—", "ndash" "–", "minus" "−",
   "lsquo" "‘", "rsquo" "’", "sbquo" "‚", "ldquo" "“", "rdquo" "”", "bdquo" "„",
   "laquo" "«", "raquo" "»", "bull" "•", "middot" "·", "deg" "°",
   "copy" "©", "reg" "®", "trade" "™", "eacute" "é", "egrave" "è", "uuml" "ü"})

(defn- decode-entities
  "str -> str
  Resolve character references: named ones from `named-entities`, and numeric
  ones (`&#8212;` / `&#x2014;`) arithmetically. An unrecognized or out-of-range
  reference is left exactly as it was found. No replacement contains a newline,
  so this cannot disturb line numbering."
  [s]
  (str/replace s #"&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);"
               (fn [[whole body]]
                 (or (if (str/starts-with? body "#")
                       (let [hex? (contains? #{\x \X} (second body))
                             digits (subs body (if hex? 2 1))]
                         (try
                           (let [cp (Long/parseLong digits (if hex? 16 10))]
                             (when (and (pos? cp) (<= cp Character/MAX_CODE_POINT))
                               (String. (Character/toChars (int cp)))))
                           (catch NumberFormatException _ nil)))
                       (named-entities (str/lower-case body)))
                     whole))))

(defn- append-newlines!
  "Append to `sb` one newline for each one in src[from,to) — the whole point of
  the scanner. Dropping a tag must not drop the lines it spanned, or every
  citation after a multi-line <a href=…> would be off by one."
  [^StringBuilder sb ^String src ^long from ^long to]
  (loop [i from]
    (when (< i to)
      (when (= (.charAt src i) \newline)
        (.append sb \newline))
      (recur (inc i)))))

(defn- index-of-ci
  "Case-insensitive .indexOf, for finding a closing </SCRIPT> written in any case.
  Returns -1 when absent. Scans with regionMatches rather than lower-casing the
  document, which would copy the whole string once per <script> on the page."
  ^long [^String src ^String needle ^long from]
  (let [limit (- (.length src) (.length needle))]
    (loop [i from]
      (cond
        (> i limit) -1
        (.regionMatches src true i needle 0 (.length needle)) i
        :else (recur (inc i))))))

(defn- tag-end
  "Index just past the '>' closing the tag that opens at `from`, quote-aware so
  that an attribute containing '>' (title=\"a > b\") doesn't end it early. Returns
  the length of src for an unterminated tag, which then swallows the remainder —
  the correct reading of markup that simply stops."
  ^long [^String src ^long from]
  (let [n (.length src)]
    (loop [i (inc from), quote nil]
      (if (>= i n)
        n
        (let [c (.charAt src i)]
          (cond
            quote        (recur (inc i) (when-not (= c quote) quote))
            (or (= c \") (= c \')) (recur (inc i) c)
            (= c \>)     (inc i)
            :else        (recur (inc i) nil)))))))

(def ^:private chrome-tags
  "Elements whose text is site furniture rather than the page's content. Their
  bodies are dropped, the way a <script> body is, because a corpus of saved pages
  otherwise accumulates a near-identical chunk of nav labels and footer addresses
  per page — embedded, indexed, and competing with real prose on short queries.

  These four are the ones HTML defines as exactly that. Deliberately *not* here:
  <main>, <article> and <section> are content; <table> is often a layout hack but
  just as often data; <form> can hold a search box or a whole questionnaire."
  #{"nav" "header" "footer" "aside"})

(def ^:private heading-tags
  "Headings survive inside a dropped chrome element. <header> is the reason: it
  marks both the site banner and an article's own title block
  (<article><header><h1>…</h1></header>), and those are indistinguishable without
  a DOM. Keeping h1-h6 means the risky half of the trade — losing the title of
  the very page you saved — cannot happen, at the price of keeping a site name
  that sits in an <h1> in the banner."
  #{"h1" "h2" "h3" "h4" "h5" "h6"})

(defn- tag-info
  "src, from, to -> [name closing? self-closing?]
  Pick apart the tag occupying src[from,to): its lower-cased element name,
  whether it is an end tag, and whether it closes itself (`<nav/>`, which opens
  nothing and so must not open a depth)."
  [^String src ^long from ^long to]
  (let [closing? (and (< (inc from) to) (= \/ (.charAt src (inc from))))
        start    (if closing? (+ from 2) (inc from))
        end      (loop [i start]
                   (if (or (>= i to)
                           (let [c (.charAt src i)]
                             (or (Character/isWhitespace c) (= c \/) (= c \>))))
                     i
                     (recur (inc i))))]
    [(str/lower-case (subs src start (max start end)))
     closing?
     (and (>= to 2) (= \/ (.charAt src (- to 2))))]))

(defn- strip
  "str -> str
  Replace every tag, comment, and <script>/<style> body with a single space, drop
  the bodies of the `chrome-tags`, and keep all newlines. A '<' that doesn't open
  a tag (`a < b`) is literal text.

  Each dropped construct becomes a space rather than nothing so that
  `<b>foo</b><i>bar</i>` reads as two words. The rare cost is markup used
  *inside* a word (`<b>Hel</b>lo` -> `Hel lo`), which is worth far less than
  joining the words of adjacent block elements into one.

  Chrome is tracked as a *depth* rather than by searching for the matching close
  tag, which is what makes nesting (a <nav> inside a <header>, an <aside> inside
  an <aside>) come out right: text is emitted only at depth 0, or inside a
  heading. The one bad case is an unclosed chrome tag, which drops the rest of
  the document — the same failure `<script>` already has, and about as rare, since
  the browsers and page-savers that produce these files close their tags."
  [^String src]
  (let [n  (.length src)
        sb (StringBuilder. n)]
    (loop [i 0, chrome 0, heading 0]
      (if (>= i n)
        (.toString sb)
        (let [visible? (or (zero? chrome) (pos? heading))
              c        (.charAt src i)]
          (if-not (= c \<)
            (do (when (or visible? (= c \newline)) (.append sb c))
                (recur (inc i) chrome heading))
            (let [rest-lc (str/lower-case (subs src i (min n (+ i 8))))]
              (cond
                ;; <!-- … --> : skip to the close, or to EOF if it never closes.
                (str/starts-with? rest-lc "<!--")
                (let [close (.indexOf src "-->" (+ i 4))
                      to    (if (neg? close) n (+ close 3))]
                  (append-newlines! sb src i to)
                  (when visible? (.append sb \space))
                  (recur to chrome heading))

                ;; <script>/<style>: the tag *and* its body are not prose.
                (or (str/starts-with? rest-lc "<script") (str/starts-with? rest-lc "<style"))
                (let [kind  (if (str/starts-with? rest-lc "<script") "</script" "</style")
                      close (index-of-ci src kind i)
                      to    (if (neg? close) n (tag-end src close))]
                  (append-newlines! sb src i to)
                  (when visible? (.append sb \space))
                  (recur to chrome heading))

                ;; A real tag opens with a name, a closing slash, or <! / <?.
                (and (< (inc i) n)
                     (let [d (.charAt src (inc i))]
                       (or (Character/isLetter d) (contains? #{\/ \! \?} d))))
                (let [to (tag-end src i)
                      [name closing? self?] (tag-info src i to)]
                  (append-newlines! sb src i to)
                  (when visible? (.append sb \space))
                  (cond
                    self?
                    (recur to chrome heading)

                    (contains? chrome-tags name)
                    (if closing?
                      ;; Leaving the outermost chrome element also ends any
                      ;; heading depth it was carrying, so an unbalanced <h2>
                      ;; inside it can't leak visibility into the rest of the page.
                      (let [chrome' (max 0 (dec chrome))]
                        (recur to chrome' (if (zero? chrome') 0 heading)))
                      (recur to (inc chrome) heading))

                    ;; Headings are only tracked while inside chrome; outside it
                    ;; everything is visible anyway.
                    (and (pos? chrome) (contains? heading-tags name))
                    (recur to chrome (if closing? (max 0 (dec heading)) (inc heading)))

                    :else
                    (recur to chrome heading)))

                ;; Not markup — "a < b".
                :else
                (do (when visible? (.append sb c))
                    (recur (inc i) chrome heading))))))))))

(defn- collapse-spaces
  "Squeeze runs of horizontal whitespace to one space, per line. HTML is written
  with generous indentation that means nothing once the tags are gone. Newlines
  are untouched — they are the locator.

  The class ends with a literal no-break space (U+00A0), which is not in \\s and
  is what &nbsp; decodes to. A reader sees a space, so leaving it would put
  U+00A0 inside the indexed text and inside every snippet a hit prints."
  [s]
  (str/replace s #"[ \t\x0B\f ]+" " "))

(defn extract
  "File -> str | nil
  Extract `file`'s text with its line structure intact, so a chunk's
  [:loc_start :loc_end] is a true source-line range. Returns nil when the page
  has no text at all (an empty document, or one that is nothing but script), in
  which case the dispatcher skips the file.

  Reads the whole file — HTML is subject to the registry's default 5 MB ceiling,
  which no hand-written page approaches."
  [^java.io.File file]
  (let [src  (String. (Files/readAllBytes (.toPath file)) StandardCharsets/UTF_8)
        text (-> src strip decode-entities collapse-spaces)]
    (when-not (str/blank? text)
      text)))
