// ---- HireMind verifier library, inlined so the hash covers it --------------------------------------
const ms = (() => {
  // Words are whitespace-separated runs that contain at least one letter or digit.
  const words = (text) => text.normalize("NFC").split(/\s+/u).filter((t) => /[\p{L}\p{N}]/u.test(t));
  const lower = (t) => t.toLowerCase().replace(/[^\p{L}\p{N}']/gu, "");

  // English: a fixed list of very common English words must make up at least 15% of the words.
  // Deterministic and readable on purpose; it does not guess, it counts.
  const COMMON_ENGLISH = new Set(
    ("the be to of and a in that have i it for not on with he as you do at this but his by from they we say her " +
      "she or an will my one all would there their what so up out if about who get which go me when make can like " +
      "time no just him know take people into year your good some could them see other than then now look only " +
      "come its over think also back after use two how our work first well way even new want because any these " +
      "give day most us is are was were been has had every down from off here where why more very own same each")
      .split(" "),
  );
  const isEnglish = (text) => {
    const ws = words(text).map(lower).filter(Boolean);
    if (ws.length < 3) return false;
    const common = ws.filter((w) => COMMON_ENGLISH.has(w)).length;
    return common / ws.length >= 0.15;
  };

  // Placeholder text: lorem ipsum, or the markers people leave when they have not written anything.
  const LOREM = new Set(
    ("lorem ipsum dolor amet consectetur consectetuer adipiscing adipisicing elit eiusmod tempor incididunt labore " +
      "dolore magna aliqua veniam nostrud exercitation ullamco laboris aliquip commodo consequat reprehenderit " +
      "voluptate cillum fugiat pariatur excepteur occaecat cupidatat proident culpa officia deserunt mollit laborum")
      .split(" "),
  );
  const placeholder = (text) => {
    const ws = words(text).map(lower);
    const lorem = new Set(ws.filter((w) => LOREM.has(w)));
    if (/lorem\s+ipsum/iu.test(text) || lorem.size >= 2) return "lorem ipsum";
    if (/\b(?:todo|tbd|fixme|placeholder|lipsum)\b|\bxxx+\b/iu.test(text)) return "placeholder marker";
    if (/\[(?:insert|your|add|enter)\b[^\]]*\]|\{\{[^}]*\}\}/iu.test(text)) return "unfilled template slot";
    return null;
  };

  const plural = (n, one) => `${n} ${one}${n === 1 ? "" : "s"}`;
  // detail: what was found, worded to read in a sentence. expected: what the test asks for. evidence: why, when it helps.
  const check = (name, passed, detail, expected, evidence) => ({ name, passed, detail, expected, evidence: evidence ?? null });

  return { words, isEnglish, placeholder, plural, check };
})();
// ---- end of library ------------------------------------------------------------------------------------
