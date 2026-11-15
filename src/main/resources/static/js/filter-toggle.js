document.addEventListener("DOMContentLoaded", function () {
  var btn = document.querySelector("[data-filter-toggle]");
  var panel = document.getElementById("jobs-filter-panel");
  if (!btn || !panel) {
    return;
  }
  btn.addEventListener("click", function () {
    var hidden = panel.classList.toggle("d-none");
    btn.setAttribute("aria-expanded", String(!hidden));
    btn.textContent = hidden ? "필터 열기" : "필터 닫기";
  });
});
