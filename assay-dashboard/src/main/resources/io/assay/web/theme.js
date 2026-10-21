(function () {
  "use strict";
  const media = matchMedia("(prefers-color-scheme: dark)");
  let choice;
  try { choice = JSON.parse(localStorage.getItem("assay.theme")); } catch { /* Use system theme. */ }
  function apply(value) {
    choice = ["light", "dark", "system"].includes(value) ? value : "system";
    document.documentElement.dataset.theme = choice === "system" ? (media.matches ? "dark" : "light") : choice;
  }
  apply(choice);
  media.addEventListener("change", () => { if (choice === "system") apply(choice); });
  window.AssayTheme = {
    choice: () => choice,
    set(value) {
      apply(value);
      try { localStorage.setItem("assay.theme", JSON.stringify(choice)); } catch { /* Storage may be disabled. */ }
    }
  };
})();
