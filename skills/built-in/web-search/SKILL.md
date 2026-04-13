---
name: web-search
description: Search the web for current information, news, and facts using SerpAPI (Google Search). Returns AI Overview summary if available, plus top organic results.
metadata:
  require-secret: true
  require-secret-description: "Enter your SerpAPI key. Get a free key (100 searches/month) at https://serpapi.com/users/sign_up"
---

# Web Search

## Instructions

Call the `run_js` tool using `index.html` with a JSON string for `data` containing:
- **query**: Required. The exact search query string.
- **lang**: Optional. Two-letter language code for results (e.g. "vi" for Vietnamese, "en" for English). Default "vi".
- **num**: Optional. Number of organic results to return (1–5). Default 3.

**When to use this skill:**
- User asks about current events, news, prices, weather, sports scores, or anything that may have changed after the model's training cutoff.
- User asks "tìm kiếm", "search for", "what is the latest", "hiện tại", "hôm nay", "mới nhất", etc.
- User needs factual verification from the web.

**Constraints:**
- Summarize the search results in 2–4 sentences. Prioritize the AI Overview if present.
- Always cite the source URL(s) used.
- Answer in the SAME language as the user's original prompt.
- Do NOT make up information; only use what is returned by the tool.
