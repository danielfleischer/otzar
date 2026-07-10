package api

import (
	"encoding/json"
	"strings"
	"testing"
)

// Verifies a hybrid server payload's score_breakdown survives unmarshal +
// re-marshal (the path `query --json` takes), and that a keyword-only hit
// without the field stays clean.
func TestScoreBreakdownRoundTrip(t *testing.T) {
	// Mirrors rag.query.hybrid output: hybrid hit carries both components,
	// a vector-only hit has keyword: null.
	server := `{"hits":[
	  {"id":"a#0","doc_id":"a","path":"/x.md","score":0.032,"text":"t","chunk_idx":0,"mtime":1,
	   "score_breakdown":{"keyword":4.5,"vector":0.81}},
	  {"id":"b#1","doc_id":"b","path":"/y.md","score":0.016,"text":"u","chunk_idx":1,"mtime":2,
	   "score_breakdown":{"keyword":null,"vector":0.79}}
	]}`

	var res QueryResult
	if err := json.Unmarshal([]byte(server), &res); err != nil {
		t.Fatal(err)
	}
	if res.Hits[0].ScoreBreakdown == nil || res.Hits[0].ScoreBreakdown.Keyword == nil {
		t.Fatal("hybrid keyword component lost")
	}
	if *res.Hits[0].ScoreBreakdown.Keyword != 4.5 || *res.Hits[0].ScoreBreakdown.Vector != 0.81 {
		t.Fatalf("wrong values: %+v", res.Hits[0].ScoreBreakdown)
	}
	if res.Hits[1].ScoreBreakdown.Keyword != nil {
		t.Fatal("keyword null should decode to nil pointer")
	}

	out, _ := json.Marshal(res)
	if !strings.Contains(string(out), `"score_breakdown":{"keyword":4.5,"vector":0.81}`) {
		t.Fatalf("score_breakdown dropped on re-marshal: %s", out)
	}

	// A non-hybrid hit with no breakdown must not sprout an empty object.
	plain := `{"hits":[{"id":"c#0","doc_id":"c","path":"/z.md","score":1.2,"text":"v","chunk_idx":0,"mtime":3}]}`
	var pres QueryResult
	json.Unmarshal([]byte(plain), &pres)
	pout, _ := json.Marshal(pres)
	if strings.Contains(string(pout), "score_breakdown") {
		t.Fatalf("omitempty failed, breakdown leaked: %s", pout)
	}
}
