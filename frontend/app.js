const DAYS = [
  ["monday", "월요일"], ["tuesday", "화요일"], ["wednesday", "수요일"],
  ["thursday", "목요일"], ["friday", "금요일"], ["saturday", "토요일"], ["sunday", "일요일"],
];

const form = document.querySelector("#planner-form");
const certificationSelect = document.querySelector("#certification");
const questionContainer = document.querySelector("#questions");
const availabilityContainer = document.querySelector("#availability");
const connectionStatus = document.querySelector("#connection-status");
const submitButton = document.querySelector("#submit-button");
const formError = document.querySelector("#form-error");
const planSection = document.querySelector("#plan-section");
let apiBaseUrl = "";
let certifications = [];
let activeQuestionDocument = null;
let questionLoadSequence = 0;

async function loadJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url} 불러오기에 실패했습니다 (${response.status}).`);
  return response.json();
}

function makeAvailabilityEditor() {
  availabilityContainer.replaceChildren();
  for (const [day, label] of DAYS) {
    const row = document.createElement("div");
    row.className = "availability-day";
    row.dataset.day = day;
    row.innerHTML = `<label class="day-toggle"><input type="checkbox" class="day-enabled"><span>${label}</span></label><div class="time-slots"></div><button type="button" class="add-slot" aria-label="${label} 시간대 추가" disabled>+ 시간대</button>`;
    const enabled = row.querySelector(".day-enabled");
    const slots = row.querySelector(".time-slots");
    const addButton = row.querySelector(".add-slot");
    enabled.addEventListener("change", () => {
      addButton.disabled = !enabled.checked;
      if (enabled.checked && slots.children.length === 0) addTimeSlot(row);
      if (!enabled.checked) slots.replaceChildren();
    });
    addButton.addEventListener("click", () => addTimeSlot(row));
    availabilityContainer.append(row);
  }
}

function addTimeSlot(dayRow) {
  const slot = document.createElement("div");
  slot.className = "time-slot";
  slot.innerHTML = '<input type="time" class="slot-start" aria-label="시작 시간" required><span>부터</span><input type="time" class="slot-end" aria-label="종료 시간" required><button type="button" class="remove-slot" aria-label="시간대 삭제">×</button>';
  slot.querySelector(".remove-slot").addEventListener("click", () => {
    slot.remove();
    if (dayRow.querySelector(".time-slots").children.length === 0) {
      dayRow.querySelector(".day-enabled").checked = false;
      dayRow.querySelector(".add-slot").disabled = true;
    }
  });
  dayRow.querySelector(".time-slots").append(slot);
}

function renderQuestions(questionDocument) {
  activeQuestionDocument = questionDocument;
  questionContainer.replaceChildren();
  const heading = document.createElement("p");
  heading.className = "question-bank-title";
  heading.textContent = `${questionDocument.certification_name} 진단 (${questionDocument.questions.length}문항)`;
  questionContainer.append(heading);
  questionDocument.questions.forEach((question, index) => {
    const fieldset = document.createElement("fieldset");
    fieldset.className = "question-card";
    const legend = document.createElement("legend");
    legend.textContent = `${index + 1}. ${question.prompt}`;
    fieldset.append(legend);
    for (const [key, choice] of Object.entries(question.choices)) {
      const label = document.createElement("label");
      label.className = "choice";
      label.innerHTML = `<input type="radio" name="answer-${question.id}" value="${key}" required><span></span>`;
      label.querySelector("span").textContent = `${key}. ${choice}`;
      fieldset.append(label);
    }
    questionContainer.append(fieldset);
  });
  submitButton.disabled = false;
}

async function loadQuestionsForCertification(certificationId) {
  const sequence = ++questionLoadSequence;
  activeQuestionDocument = null;
  questionContainer.innerHTML = '<p class="muted">선택한 자격증의 질문을 불러오는 중…</p>';
  submitButton.disabled = true;
  try {
    const document = await loadJson(
      `${apiBaseUrl.replace(/\/$/, "")}/certifications/${encodeURIComponent(certificationId)}/questions`,
    );
    if (sequence !== questionLoadSequence) return;
    if (document.certification_id !== certificationId) {
      throw new Error("질문 데이터의 certification_id가 선택한 자격증과 일치하지 않습니다.");
    }
    renderQuestions(document);
  } catch (error) {
    if (sequence !== questionLoadSequence) return;
    questionContainer.innerHTML = "";
    const message = document.createElement("p");
    message.className = "error-text";
    message.textContent = error.message;
    questionContainer.append(message);
  }
}

function renderCertificationOptions() {
  certificationSelect.replaceChildren(new Option("자격증을 선택하세요", ""));
  for (const certification of certifications) {
    certificationSelect.add(new Option(certification.name, certification.id));
  }
  certificationSelect.disabled = false;
  if (certifications.length === 1) certificationSelect.value = certifications[0].id;
  certificationSelect.dispatchEvent(new Event("change"));
}

function toApiDateTime(value) {
  return value ? value.replace("T", "/").replaceAll("-", "/").replaceAll(":", "/") : "";
}

function collectWeeklyAvailability() {
  const weeklyAvailability = Object.fromEntries(DAYS.map(([day]) => [day, []]));
  for (const row of availabilityContainer.querySelectorAll(".availability-day")) {
    if (!row.querySelector(".day-enabled").checked) continue;
    const windows = [...row.querySelectorAll(".time-slot")].map((slot) => ({
      start: slot.querySelector(".slot-start").value,
      end: slot.querySelector(".slot-end").value,
    }));
    if (!windows.length || windows.some((window) => !window.start || !window.end || window.start >= window.end)) {
      throw new Error(`${row.querySelector(".day-toggle span").textContent} 시간대를 확인해 주세요. 시작 시간은 종료 시간보다 빨라야 합니다.`);
    }
    weeklyAvailability[row.dataset.day] = windows;
  }
  if (!Object.values(weeklyAvailability).some((windows) => windows.length)) {
    throw new Error("공부 가능한 요일과 시간대를 하나 이상 입력해 주세요.");
  }
  return weeklyAvailability;
}

function collectAssessmentResults() {
  return {
    certification_id: activeQuestionDocument.certification_id,
    results: activeQuestionDocument.questions.map((question) => {
      const answer = form.querySelector(`input[name="answer-${question.id}"]:checked`);
      if (!answer) throw new Error("모든 진단 질문에 답해 주세요.");
      return {
        problem_id: question.id,
        question: question.prompt,
        choices: question.choices,
        correct_answer: question.correct_answer,
        user_answer: answer.value,
        is_correct: answer.value === question.correct_answer,
        possible_score: question.possible_score ?? 1,
      };
    }),
  };
}

function parseApiDate(value) {
  const [year, month, day, hour, minute] = value.split("/").map(Number);
  return new Date(year, month - 1, day, hour, minute);
}

function mondayOf(date) {
  const monday = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
  return monday;
}

function dateLabel(date) {
  return new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", weekday: "short" }).format(date);
}

function renderPlan(result, preparationStart) {
  const startWeek = mondayOf(preparationStart);
  const groups = new Map();
  for (const schedule of result.schedules ?? []) {
    // The API also returns existing external events; this view shows study blocks only.
    if (!schedule.schedule_id?.startsWith("study-")) continue;
    const start = parseApiDate(schedule.start_date);
    const end = parseApiDate(schedule.end_date);
    const weekNumber = Math.floor((mondayOf(start) - startWeek) / (7 * 24 * 60 * 60 * 1000)) + 1;
    if (!groups.has(weekNumber)) groups.set(weekNumber, []);
    groups.get(weekNumber).push({ ...schedule, start, end });
  }
  const weekList = document.querySelector("#plan-weeks");
  weekList.replaceChildren();
  for (const [weekNumber, schedules] of [...groups.entries()].sort((a, b) => a[0] - b[0])) {
    const section = document.createElement("section");
    section.className = "week-card";
    const title = document.createElement("h3");
    title.textContent = `${weekNumber}주차`;
    section.append(title);
    schedules.sort((a, b) => a.start - b.start).forEach((schedule) => {
      const item = document.createElement("article");
      item.className = "schedule-item";
      const time = `${new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false }).format(schedule.start)}–${new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false }).format(schedule.end)}`;
      item.innerHTML = `<div class="schedule-date"></div><div class="schedule-detail"><strong class="schedule-topic"></strong><span class="schedule-time"></span></div>`;
      item.querySelector(".schedule-date").textContent = dateLabel(schedule.start);
      item.querySelector(".schedule-topic").textContent = schedule.topic || "학습";
      item.querySelector(".schedule-time").textContent = time;
      section.append(item);
    });
    weekList.append(section);
  }
  if (!groups.size) weekList.innerHTML = '<div class="empty-plan">생성된 학습 일정이 없습니다. 입력 기간과 가용 시간을 확인해 주세요.</div>';
  document.querySelector("#agent-summary").textContent = result.agent_summary || "계획을 생성했습니다.";
  const csvOutput = document.querySelector("#csv-output");
  csvOutput.textContent = result.schedule_csv_file
    ? `일정 CSV 저장 위치: backend/${result.schedule_csv_file}`
    : "";
  csvOutput.hidden = !result.schedule_csv_file;
  const csvDownload = document.querySelector("#csv-download");
  csvDownload.href = result.schedule_csv_url
    ? new URL(result.schedule_csv_url, `${apiBaseUrl.replace(/\/$/, "")}/`).toString()
    : "#";
  csvDownload.hidden = !result.schedule_csv_url;
  planSection.hidden = false;
  planSection.scrollIntoView({ behavior: "smooth", block: "start" });
}

certificationSelect.addEventListener("change", () => {
  if (certificationSelect.value) loadQuestionsForCertification(certificationSelect.value);
  else {
    activeQuestionDocument = null;
    questionContainer.innerHTML = '<p class="muted">자격증을 선택하면 진단 질문이 표시됩니다.</p>';
    submitButton.disabled = true;
  }
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  formError.hidden = true;
  formError.textContent = "";
  planSection.hidden = true;
  submitButton.disabled = true;
  submitButton.textContent = "계획 생성 중…";
  try {
    const preparationStartValue = document.querySelector("#preparation-start").value;
    const examDateValue = document.querySelector("#exam-date").value;
    if (!preparationStartValue || !examDateValue || new Date(examDateValue) <= new Date(preparationStartValue)) {
      throw new Error("시험일은 준비 시작보다 뒤의 날짜와 시간이어야 합니다.");
    }
    const payload = {
      certification_id: certificationSelect.value,
      preparation_start: toApiDateTime(preparationStartValue),
      exam_date: toApiDateTime(examDateValue),
      self_assessment: document.querySelector("#self-assessment").value.trim(),
      assessment_results: collectAssessmentResults(),
      weekly_availability: collectWeeklyAvailability(),
    };
    const response = await fetch(`${apiBaseUrl.replace(/\/$/, "")}/study-plans`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error?.message || `계획 생성에 실패했습니다 (${response.status}).`);
    renderPlan(result, new Date(preparationStartValue));
  } catch (error) {
    formError.textContent = error.message;
    formError.hidden = false;
  } finally {
    submitButton.disabled = !activeQuestionDocument;
    submitButton.textContent = "개인화 계획 만들기";
  }
});

document.querySelector("#new-plan").addEventListener("click", () => {
  planSection.hidden = true;
  form.scrollIntoView({ behavior: "smooth", block: "start" });
});

makeAvailabilityEditor();
Promise.all([
  loadJson("./frontend_config.json"),
]).then(async ([config]) => {
  apiBaseUrl = config.api_base_url;
  const apiRoot = apiBaseUrl.replace(/\/$/, "");
  const [health, catalog] = await Promise.all([
    loadJson(`${apiRoot}/health`),
    loadJson(`${apiRoot}/certifications`),
  ]);
  if (health.status !== "ok") throw new Error("Planning API 상태 확인에 실패했습니다.");
  certifications = catalog.certifications;
  if (!certifications.length) throw new Error("선택 가능한 자격증이 없습니다.");
  renderCertificationOptions();
  connectionStatus.textContent = `Planning API 연결됨 · ${apiBaseUrl}`;
  connectionStatus.classList.add("ready");
}).catch((error) => {
  connectionStatus.textContent = error.message;
  connectionStatus.classList.add("error");
});
