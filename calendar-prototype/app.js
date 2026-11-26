'use strict';

const PHONE_DIALOGS = ['#info','#time-conflict-prompt','#add','#edit-schedule','#edit-exam','#adjust-plan','#calendar-conflicts','#rename-plan','#delete-plan','#export-plan','#sync-calendar','#delete-study','#today-schedules','#replan-diagnosis-prompt'];
const phoneDialogMotion = new Map();
let phoneDialogBackgroundLock=null;
function lockPhoneDialogBackground() {
  if(phoneDialogBackgroundLock)return;
  const calendar=document.querySelector('#app');
  const surfaces=['#app','#fab-create-actions','#quick-overview-sheet','#plans-drawer','#move-preview','#plan-adjustment-preview','#plan-delete-undo','#study-delete-undo','#calendar-return-notice'].map(selector=>document.querySelector(selector)).filter(Boolean);
  phoneDialogBackgroundLock={inert:Boolean(calendar.inert),surfaces:surfaces.map(node=>({node,inert:Boolean(node.inert)}))};
  surfaces.forEach(node=>{node.inert=true;});
  document.body.classList.add('popup-scroll-locked');
  document.querySelector('.phone').classList.add('popup-interaction-locked');
}
function unlockPhoneDialogBackground() {
  if(PHONE_DIALOGS.some(selector=>{const dialog=document.querySelector(selector);return dialog.open || dialog.classList.contains('popup-closing');}) || !phoneDialogBackgroundLock)return;
  phoneDialogBackgroundLock.surfaces.forEach(({node,inert})=>{node.inert=inert;});
  phoneDialogBackgroundLock=null;
  document.body.classList.remove('popup-scroll-locked');
  document.querySelector('.phone').classList.remove('popup-interaction-locked');
}
function preventPopupBackgroundScroll(event) {
  if(!phoneDialogBackgroundLock)return;
  const dialog=event.target.closest?.('dialog[open]');
  if(dialog && !dialog.classList.contains('popup-closing')){
    if(event.target!==dialog)return;
    const point=event.touches?.[0] || event,rect=dialog.getBoundingClientRect();
    if(point.clientX>=rect.left && point.clientX<=rect.right && point.clientY>=rect.top && point.clientY<=rect.bottom)return;
  }
  event.preventDefault();
}
document.addEventListener('wheel',preventPopupBackgroundScroll,{passive:false,capture:true});
document.addEventListener('touchmove',preventPopupBackgroundScroll,{passive:false,capture:true});
// Keep native dialog focus/keyboard behavior, anchored to the prototype phone.
PHONE_DIALOGS.forEach(selector=>document.querySelector('.phone').append(document.querySelector(selector)));
function updatePhoneDialogBounds() {
  const rect=document.querySelector('.phone').getBoundingClientRect?.();
  if(!rect)return;
  PHONE_DIALOGS.forEach(selector=>{
    const dialog=document.querySelector(selector);
    dialog.style.setProperty('--dialog-center-x',`${rect.left+rect.width/2}px`);
    dialog.style.setProperty('--dialog-center-y',`${rect.top+rect.height/2}px`);
    dialog.style.setProperty('--dialog-phone-width',`${rect.width}px`);
    dialog.style.setProperty('--dialog-phone-height',`${rect.height}px`);
  });
}
function showPhoneDialog(selector) {
  updatePhoneDialogBounds();
  const dialog=document.querySelector(selector);
  if(dialog.open)return;
  dialog.showModal();
  dialog.scrollTop=0;
  dialog.querySelectorAll('*').forEach(element=>{element.scrollTop=0;});
  lockPhoneDialogBackground();
  document.querySelector('.phone').classList.add('popup-shown');
  if(!window.matchMedia('(prefers-reduced-motion: reduce)').matches && dialog.animate){
    const animation=dialog.animate([
      {opacity:0,transform:'translate(-50%,calc(-50% + 12px)) scale(.97)'},
      {opacity:1,transform:'translate(-50%,-50%) scale(1)'}
    ],{duration:240,easing:'cubic-bezier(.22,.75,.18,1)'});
    phoneDialogMotion.set(dialog,{animation});
  }
}
function closePhoneDialog(selector,returnValue='') {
  const dialog=document.querySelector(selector),state=phoneDialogMotion.get(dialog);
  if(state?.closing)return state.closing;
  if(!dialog.open)return Promise.resolve();
  const current=getComputedStyle(dialog);
  const frame={opacity:current.opacity,transform:current.transform};
  state?.animation?.cancel();
  dialog.classList.add('popup-closing');
  document.querySelector('.phone').classList.remove('popup-shown');
  const animation=!window.matchMedia('(prefers-reduced-motion: reduce)').matches && dialog.animate
    ? dialog.animate([frame,{opacity:0,transform:'translate(-50%,calc(-50% + 10px)) scale(.97)'}],{duration:180,easing:'cubic-bezier(.4,0,.7,.3)',fill:'forwards'}) : null;
  const finish=()=>{
    dialog.close(returnValue);
    animation?.cancel();
    dialog.classList.remove('popup-closing');
    phoneDialogMotion.delete(dialog);
    unlockPhoneDialogBackground();
    if(selector==='#add' && fabScheduleReturn){fabScheduleReturn=null;setFabCreateMenu(true,true);}
  };
  if(!animation){finish();return Promise.resolve();}
  const closing=animation.finished.catch(()=>{}).then(finish);
  phoneDialogMotion.set(dialog,{animation,closing});
  return closing;
}
PHONE_DIALOGS.forEach(selector=>{
  const dialog=document.querySelector(selector);
  dialog.addEventListener('close',unlockPhoneDialogBackground);
  let backdropPressed=false;
  const onDarkArea=event=>{
    const rect=dialog.getBoundingClientRect(),phone=document.querySelector('.phone').getBoundingClientRect();
    return event.target===dialog && event.clientX>=phone.left && event.clientX<=phone.right && event.clientY>=phone.top && event.clientY<=phone.bottom
      && (event.clientX<rect.left || event.clientX>rect.right || event.clientY<rect.top || event.clientY>rect.bottom);
  };
  dialog.addEventListener('pointerdown',event=>{backdropPressed=onDarkArea(event);});
  dialog.addEventListener('click',event=>{
    if(backdropPressed && onDarkArea(event))closePhoneDialog(selector);
    backdropPressed=false;
  });
  dialog.addEventListener('cancel',event=>{event.preventDefault();closePhoneDialog(selector);});
  dialog.addEventListener('submit',event=>{
    if(event.target.getAttribute('method')!=='dialog')return;
    event.preventDefault();closePhoneDialog(selector,event.submitter?.value || '');
  });
});
window.addEventListener('resize',updatePhoneDialogBounds);
window.addEventListener('scroll',updatePhoneDialogBounds,true);

let TODAY = '2026-11-25';
function plannerWallDay(){return new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());}
let lastPlannerWallDay=plannerWallDay();
function refreshPlannerDay(){
  const day=plannerWallDay(),elapsed=Math.round((Date.parse(day)-Date.parse(lastPlannerWallDay))/86400000);
  if(elapsed<=0)return;
  lastPlannerWallDay=day;
  const next=new Date(TODAY+'T12:00:00');next.setDate(next.getDate()+elapsed);TODAY=dateKey(next);
  syncPlanActivity();renderPlanList();renderCalendar();
}
const EXAM_DATE = '2026-11-30';
const makePlans = () => [
  {id:'engineer', certificateId:'engineer', name:'정보처리기사 필기', subtitle:'정보처리기사 · 필기', examDate:EXAM_DATE, active:true, color:'#2b8a57'},
  {id:'sqld', certificateId:'sqld', name:'SQL 개발자(SQLD)', subtitle:'SQL 개발자 · SQLD', examDate:'2026-12-03', active:true, color:'#5572ad'}
].map(plan=>{
  const samples=plan.id==='engineer'?[['2026-11-05',[3,3,3]],['2026-11-10',[0,3,3]],['2026-11-15',[0,1,3]],['2026-11-20',[3,1,3]],['2026-11-24',[0,1,3]]]:[['2026-11-07',[0,3,3]],['2026-11-14',[0,1,3]],['2026-11-21',[0,3,2]],['2026-11-25',[0,1,2]]];
  return {...plan,diagnosisHistory:samples.map(([date,answers])=>({date,answers,score:answers.filter((answer,index)=>answer===index).length,total:3,demo:true}))};
});
let plans = makePlans();
const planActivityState=new WeakMap();
function syncPlanActivity(today=TODAY){
  for(const plan of plans){
    const previous=planActivityState.get(plan);
    const manual=previous && plan.active===previous.applied?previous.manual:Boolean(plan.active);
    const inPeriod=(!plan.startDate || plan.startDate<=today) && (!plan.examDate || today<=plan.examDate);
    plan.active=manual && inPeriod;
    planActivityState.set(plan,{manual,applied:plan.active});
  }
}
function hasTodayDiagnosis(plan){
  return [...(plan.diagnosisHistory || []),...(plan.latestDiagnosis?[plan.latestDiagnosis]:[])].some(record=>record.date===TODAY);
}
function todayDiagnosisOrder(plan){return Math.max(0,...(plan.diagnosisHistory || []).filter(record=>record.date===TODAY).map(record=>record.completedOrder || 0));}
function diagnosisRecencyLabel(plan){
  const dates=[...(plan.diagnosisHistory || []),...(plan.latestDiagnosis?[plan.latestDiagnosis]:[])].map(record=>record.date).filter(date=>typeof date==='string' && /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(Date.parse(date)) && date<=TODAY).sort();
  if(dates.length){
    const days=Math.round((Date.parse(TODAY)-Date.parse(dates.at(-1)))/86400000);
    return days===0?'오늘 진단했어요':`마지막 진단 ${days}일 전`;
  }
  const elapsed=plan.startDate && plan.startDate<=TODAY?Math.floor((Date.parse(TODAY)-Date.parse(plan.startDate))/86400000):null;
  return Number.isFinite(elapsed)?`진단 기록 없음 · 준비 시작 후 ${elapsed}일`:'아직 자가 진단 기록이 없어요';
}
let planSectionReturn=null,planStatusOverview=false,topicDetailReturn=null,planTopicsOverview=false,selectedPlan=null,selectedTopic=null,planDetailReturnView=null,calendarSpotlight=null,calendarSpotlightTimer=null;
let topicCalendarReturn=null,topicStatusFilter=null,planStatusFilter=null,conflictCalendarReturn=null,planSchedulesExpanded=false;
const detailListMotions=new Set();
let planCreation=null,createdPlanSequence=0,creationBrush=null,creationBrushClickUntil=0;
let creationUndo=null,creationUndoTimer=null;
let plansDrawerOpen = false, plansDrawerClosing = null;
const STATUS = {
  done: { label: '다 했어요', color: '#48a66c', note: '계획한 학습을 모두 마쳤어요.' },
  partial: { label: '일부 했어요', color: '#f2ce55', note: '계획한 학습 중 일부를 진행했어요.' },
  missed: { label: '못 했어요', color: '#d85a61', note: '이번 학습을 진행하지 못했어요.' },
  unknown: { label: '미확인', color: '#55585f', note: '학습한 만큼, 결과를 선택해주세요.' }
};
const SEEDS = [
  [2, '이론 학습', 'done'], [5, '기출 풀이', 'partial'], [7, '친구 약속', 'existing'],
  [9, '핵심 개념', 'done'], [11, '병원 예약', 'existing'], [13, '오답 정리', 'missed'],
  [14, '주말 여행', 'existing'], [15, '주말 여행', 'existing'], [16, '기출 풀이', 'done'],
  [18, '실기 연습', 'partial'], [20, '모의 시험', 'missed'], [22, '가족 식사', 'existing'],
  [23, '이론 복습', 'done'], [24, '기출 풀이', 'partial'], [24, '오답 정리', 'missed'],
  [25, '기출 풀이', 'done'], [25, '오답 정리', 'partial'], [25, '실기 연습', 'missed'],
  [26, '팀 회의', 'existing'], [26, '모의 시험', 'unknown'], [27, '이론 복습', 'unknown'],
  [28, '기출 풀이', 'unknown'], [30, '모의 시험', 'unknown'],
  [24, '수업', 'existing'], [27, '병원 방문', 'existing'], [28, '가족 모임', 'existing'],
  [26, 'SQL 기초', 'unknown', 'sqld'], [28, 'JOIN 연습', 'unknown', 'sqld'], [29, 'SQL 실전', 'unknown', 'sqld']
];
const CONTENT = {
  'SQL 기초': 'SQL 기본 문법 학습\nSELECT · WHERE · ORDER BY\n예제 쿼리를 직접 작성해요.',
  'JOIN 연습': 'SQLD 기출 JOIN 문제 풀이\nINNER JOIN · OUTER JOIN\n조건에 따른 결과의 차이를 정리해요.',
  'SQL 실전': 'SQLD 실전 문제 풀이\n기출문제 20문항\n문제를 풀고 해설을 확인해요.',
  '기출 풀이': '데이터베이스 기출문제 풀이\n2025년 1회 · 21–40번 (20문항)\n문제를 풀고, 틀린 문항의 해설을 정리해요.',
  '이론 학습': '소프트웨어 설계 핵심 이론\n교재 1장 · 요구사항 확인\n핵심 용어를 읽고 개념 노트를 정리해요.',
  '핵심 개념': '데이터베이스 핵심 개념\n정규화 · 트랜잭션 · 인덱스\n개념별 예제를 풀며 차이점을 정리해요.',
  '이론 복습': '소프트웨어 개발 핵심 이론 복습\n자료구조 · 알고리즘\n헷갈렸던 개념을 다시 읽고 요약해요.',
  '오답 정리': '기출문제 오답 노트 정리\n틀린 문항 10개 다시 풀기\n오답의 원인과 관련 개념을 기록해요.',
  '실기 연습': 'SQL 실습 문제 풀이\nJOIN · GROUP BY · 서브쿼리\n예제 쿼리를 직접 작성하고 결과를 확인해요.',
  '모의 시험': '정보처리기사 필기 모의시험\n기출 1회분 · 100문항\n시간을 재고 풀며 취약 과목을 확인해요.'
};
const pad = n => String(n).padStart(2, '0');
const dateKey = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parseDate = s => new Date(`${s}T12:00:00`);
const escapeHTML = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const makeSeeds = () => SEEDS.map(([day, title, status, planId = 'engineer'], i) => ({
  id: `sample-${i}`, date: `2026-11-${pad(day)}`, title, type: status === 'existing' ? 'calendar' : 'study',
  status: status === 'existing' ? 'unknown' : status,
  planId:status === 'existing' ? null : planId,
  start: planId === 'sqld' ? '10:00' : title === '오답 정리' || title === '수업' ? '16:00' : title === '실기 연습' ? '18:00' : '14:00',
  end: planId === 'sqld' ? '11:00' : title === '오답 정리' || title === '수업' ? '17:00' : title === '실기 연습' ? '19:00' : title === '모의 시험' ? '16:00' : '15:00',
  content: CONTENT[title] || '연결된 캘린더에서 가져온 예시 일정입니다.'
}));
let events = makeSeeds();
const normalizedProgress = value => typeof value === 'number' && Number.isFinite(value) ? Math.min(100, Math.max(0, Math.round(value))) : 50;
const statusFromProgress = value => value === 100 ? 'done' : value === 0 ? 'missed' : 'partial';
const progressForEvent = e => e.status === 'done' ? 100 : e.status === 'missed' || e.status === 'unknown' ? 0 : normalizedProgress(e.progress);
const validSchedule = e => e && /^\d{4}-\d{2}-\d{2}$/.test(e.date) && !Number.isNaN(parseDate(e.date).getTime()) && /^([01]\d|2[0-3]):[0-5]\d$/.test(e.start) && /^(?:([01]\d|2[0-3]):[0-5]\d|24:00)$/.test(e.end) && e.start < e.end;
const planFor = e => plans.find(plan => plan.id === (e?.planId || 'engineer')) || (planCreation?.preview && planCreation.preview.plan.id===e?.planId?planCreation.preview.plan:undefined);
const isEventVisible = e => e.type === 'calendar' || Boolean(planFor(e)?.active) || calendarSpotlight===e.id || Boolean(planCreation?.preview && e.planId===planCreation.preview.plan.id);
const examDateFor = e => planFor(e)?.examDate || EXAM_DATE;
const isMovableStudy = e => e?.type === 'study' && (isEventVisible(e)||e.isPlanAdjustmentPreview) && e.date >= TODAY;
const canEditPlanDayEvent = e => Boolean(planTimeEdit && e && e.date===planTimeEdit.before.date);
const canEditConflictDayEvent = e => Boolean(normalConflictDay && calendarView==='day' && e && e.date===normalConflictDay.date);
const canMoveEvent = e => isMovableStudy(e) || canEditPlanDayEvent(e) || canEditConflictDayEvent(e);
const canMoveToDate = (e, date) => e?.isCreationPreview ? date>=planCreation.startDate && date<=examDateFor(e) : (canEditPlanDayEvent(e) && date===planTimeEdit.before.date) || (canEditConflictDayEvent(e) && date===normalConflictDay.date) || (date >= TODAY && date <= examDateFor(e));
function moveRestriction(e, date) {
  if(e?.isCreationPreview && date<planCreation.startDate)return '준비 시작일 이전으로는 학습 일정을 옮길 수 없어요.';
  if(canEditPlanDayEvent(e) && date===planTimeEdit.before.date)return '';
  if(canEditConflictDayEvent(e) && date===normalConflictDay.date)return '';
  if (!canMoveEvent(e)) return '과거 학습 기록은 날짜와 시간을 수정할 수 없어요.';
  if (date < TODAY) return '오늘·미래 일정은 과거 날짜로 옮길 수 없어요.';
  if (date > examDateFor(e)) return '시험일 이후로는 학습 일정을 옮길 수 없어요.';
  return '';
}
// Design preview: all edits live in memory and reset on every page load.
events.forEach(e => {
  e.progress = progressForEvent(e);
  if (e.status === 'partial') e.status = statusFromProgress(e.progress);
});
let viewDate = parseDate(TODAY), selectedDate = TODAY, selectedEvent = null, searchOpen = false;
let calendarView = 'month';
let focusAfterRender = null, toastTimer;
const app = document.querySelector('#app');
const paths = {
  code: '<path d="m8 7-5 5 5 5M16 7l5 5-5 5M14 4l-4 16"/>',
  database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
  monitor: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
  award: '<circle cx="12" cy="8" r="5"/><path d="m8 12-2 9 6-3 6 3-2-9"/>',
  edit: '<path d="m16 3 5 5-12 12-6 1 1-6Z"/><path d="m14 5 5 5"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 1 1 4.4 1.6c-.9.6-1.9 1-1.9 2.4"/><circle cx="12" cy="17" r=".9" fill="currentColor" stroke="none"/>',
  alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6"/><circle cx="12" cy="17" r=".9" fill="currentColor" stroke="none"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>',
  back: '<path d="m15 5-7 7 7 7"/>',
  prev: '<path d="m14 7-5 5 5 5"/>', next: '<path d="m10 7 5 5-5 5"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="3"/><path d="M7 3v4M17 3v4M3 10h18"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>'
};
const svg = name => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
const iconButton = (action, label, icon) => `<button class="icon-btn" data-action="${action}" aria-label="${label}">${svg(icon)}</button>`;
function calendarEvents() {
  if(planCreation?.preview){const preview=planCreation.preview;return [...events.filter(e=>!preview.replaceIds?.has(e.id)),...preview.events.map(e=>({...e,isCreationPreview:true}))];}
  if(!planCalendarPreview && !normalConflictDay)return events;
  const changes=new Map((normalConflictDay?.items || planCalendarPreview?.items || []).map(item=>[item.id,item]));
  return events.map(e=>{
    const item=changes.get(e.id);
    const after=planTimeEdit?.id===e.id?planTimeEdit.draft:item?.after;
    return after?{...e,...after,isPlanAdjustmentPreview:Boolean(planCalendarPreview && (planTimeEdit?.id===e.id || item.manuallyAdjusted || ['date','start','end'].some(key=>e[key]!==after[key]))),dismissedConflict:['date','start','end'].some(key=>e[key]!==after[key])?'':e.dismissedConflict}:e;
  });
}
const overlappingEvents = event => calendarEvents().filter(other => (isEventVisible(other)||other.isPlanAdjustmentPreview) && other.id !== event.id && other.date === event.date && other.start < event.end && event.start < other.end);
const conflictSignature = event => JSON.stringify([event.date, event.start, event.end, overlappingEvents(event).map(e => [e.id, e.start, e.end]).sort()]);
const hasConflict = event => !event.isReplacementOriginal && event.type === 'study' && overlappingEvents(event).length > 0 && event.dismissedConflict !== conflictSignature(event);
const calendarConflicts=()=>calendarEvents().filter(e=>e.type==='study' && (planFor(e)?.active || e.isCreationPreview || e.isPlanAdjustmentPreview) && hasConflict(e));
function conflictIndicator(){
  const count=calendarConflicts().length,label=count?`충돌 일정 ${count}개 확인`:'충돌 일정 없음';
  return `<button type="button" class="drawer-conflict-button conflict-indicator${count?' active':''}" data-action="calendar-conflicts" ${count?'':'disabled aria-disabled="true"'} aria-label="${label}" title="${label}">${svg('alert')}<span>충돌 일정</span><small>${count?count+'개':'없음'}</small></button>`;
}
function pastUnfinishedEvents(id){return events.filter(e=>e.type==='study' && (!id || e.planId===id) && plans.some(plan=>plan.id===e.planId) && e.date<TODAY && ['partial','missed','unknown'].includes(e.status));}
function plansMenuButton(){
  const conflicts=calendarConflicts().length>0,overdue=plans.some(plan=>isPlanBehind(plan.id)),active=conflicts || overdue;
  const description=[conflicts?'충돌 일정 있음':'',overdue?'예상 학습 진행률보다 뒤처진 계획 있음':''].filter(Boolean).join(', ');
  return `<button type="button" class="icon-btn plans-menu-button" data-action="plans" aria-label="계획 목록" ${active?`aria-description="${description}"`:''}>${svg('menu')}${active?'<span class="plans-conflict-dot" aria-hidden="true"></span>':''}</button>`;
}
document.querySelector('#calendar-conflict-list').addEventListener('click',async action=>{
  const button=action.target.closest('[data-conflict-event]');
  const id=button?.dataset.conflictEvent;
  if(!id || !calendarConflicts().some(e=>e.id===id))return;
  const previous={calendarView,viewDate:new Date(viewDate),selectedDate,scrollTop:document.querySelector('#calendar-conflicts').scrollTop || 0,listScrollTop:document.querySelector('#calendar-conflict-list').scrollTop || 0,eventId:id};
  await closePhoneDialog('#calendar-conflicts');
  const event=movePreviewEvent(id);if(event){conflictCalendarReturn=previous;locateCalendarSchedule(event,false,true);}
});
function showCalendarConflicts(allowEmpty=false){
  const conflicts=calendarConflicts();if(!conflicts.length && !allowEmpty)return;
  document.querySelector('#calendar-conflict-list').innerHTML=conflicts.map(e=>`<button type="button" class="calendar-conflict-item" data-conflict-event="${e.id}" aria-label="${escapeHTML(e.title)} 주 보기에서 확인"><strong>${escapeHTML(e.title)}</strong><span class="calendar-conflict-time">${longDate(e.date)} · ${e.start}–${e.end}</span><small>${overlappingEvents(e).map(other=>`${escapeHTML(other.title)} (${other.start}–${other.end})`).join('<br>')}</small></button>`).join('') || '<p class="empty">충돌 일정이 없어요.</p>';
  showPhoneDialog('#calendar-conflicts');
}
const eventClass = e => e.type === 'calendar' ? 'existing' : e.status;
const byDate = date => calendarEvents().filter(e => (isEventVisible(e)||e.isPlanAdjustmentPreview) && e.date === date).sort((a, b) => a.start.localeCompare(b.start) || a.id.localeCompare(b.id));
function longDate(date) {
  return new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(parseDate(date));
}
function timeLabel(time) {
  const [h, m] = time.split(':').map(Number);
  return `${h < 12 ? '오전' : '오후'} ${h % 12 || 12}:${pad(m)}`;
}
function duration(e) {
  const minutes = t => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));
  const diff = minutes(e.end) - minutes(e.start);
  return `${Math.floor(diff / 60) ? `${Math.floor(diff / 60)}시간` : ''}${diff % 60 ? ` ${diff % 60}분` : ''}`.trim();
}
function toast(message) {
  const el = document.querySelector('#toast');
  el.textContent = message; el.classList.add('visible');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('visible'), 2500);
}
function eventButton(e) {
  const conflict = hasConflict(e);
  const group=e.isReplacementOriginal?'original':planCreation?.reschedulePlanId && e.isCreationPreview?'new':null;
  const highlight=planCreation?.preview?.highlightGroup;
  const readOnly=Boolean(planCreation?.preview && !e.isCreationPreview);
  const movable = planCreation?.preview?Boolean(e.isCreationPreview):(planTimeEdit?canEditPlanDayEvent(e):Boolean(e.isPlanAdjustmentPreview) || (!planCalendarPreview && (isMovableStudy(e)||canEditConflictDayEvent(e))));
  return `<button class="event ${group?'replan-'+group+'-event':''} ${group==='new'?'replan-default-pulse':''} ${group && highlight===group?'replan-group-highlight':''} ${group && highlight && highlight!==group?'replan-group-muted':''} ${eventClass(e)} ${quickOverviewOpen && quickStatusFilter && e.type==='study' && e.status===quickStatusFilter && planFor(e)?.active?'quick-status-highlight':''} ${calendarSpotlight===e.id?'calendar-spotlight':''} ${readOnly?'creation-preview-readonly':''} ${e.isCreationPreview?'creation-preview-event':''} ${e.isPlanAdjustmentPreview?'plan-adjustment-event':''} ${movable ? 'movable-event' : ''} ${e.type === 'study' && e.date < TODAY ? 'past' : ''}" ${group?'data-replan-event-group="'+group+'"':''} data-event="${e.id}" ${readOnly?'disabled aria-disabled="true" tabindex="-1" title="기존 일정 · 미리보기에서는 선택할 수 없어요"':''} ${movable ? 'data-movable="true" title="드래그하여 날짜 이동 · M 키로 이동"' : ''} aria-label="${escapeHTML(e.title)}${group?', '+(group==='original'?'대체할 기존 일정':'새로 배정할 일정'):''}, ${e.type === 'calendar' ? '기존 일정' : STATUS[e.status].label}${readOnly?', 기존 일정, 선택 불가':''}${e.isPlanAdjustmentPreview?', 변경 예정':''}${conflict ? ', 다른 일정과 충돌' : ''}"><span class="event-title">${group?`<span class="replan-group-badge">${group==='original'?'대체 전':'새 배정'}</span> `: ''}${escapeHTML(e.title)}</span>${conflict ? '<span class="warning" aria-hidden="true">!</span>' : ''}</button>`;
}
function renderCalendar() {
  syncPlanActivity();
  if(selfDiagnosis){renderSelfDiagnosis();return;}
  if(!phoneDialogBackgroundLock)setFabCreateMenu(false);
  agendaModeMotion?.finish(false);
  const quickSheet=document.querySelector('#quick-overview-sheet');
  if(quickSheet.parentElement?.classList.contains('agenda'))document.querySelector('.phone').append(quickSheet);
  syncQuickOverview(false);
  if(activePlansOverview && !selectedEvent){renderActivePlansOverview();return;}
  if(settingsReturn){renderSettings();return;}
  clearMonthAgendaContentMotion();
  document.querySelector('.phone').classList.toggle('creation-preview-active',Boolean(planCreation?.preview));
  clearReplanEmphasisMotions();
  clearReplanExamMotions();
  detailNavigationMotion?.finish();
  document.querySelector('.phone').classList.toggle('has-calendar-return',Boolean(topicCalendarReturn || conflictCalendarReturn));
  if(planCreation && !planCreation.preview){renderPlanCreation();return;}
  if(selectedPlan && !planCreation && !planCalendarPreview && !normalConflictDay){renderPlanDetail();return;}
  if(planCreation?.preview && calendarView==='week')planCreation.preview.week=creationWeekKey(dateKey(viewDate));
  const year = viewDate.getFullYear(), month = viewDate.getMonth();
  const unit = {year:'년',month:'달',week:'주',day:'일'}[calendarView];
  const heading = calendarView === 'year' ? `${year}년` : calendarView === 'day' ? `${month + 1}월 ${viewDate.getDate()}일` : `${month + 1}월`;
  let html = `<header class="toolbar calendar-toolbar view-${calendarView}">${plansMenuButton()}<button class="month-title" data-action="today" aria-label="기준일 ${longDate(TODAY)}로 이동">${heading}</button><span class="year">${calendarView === 'year' ? '' : year}</span>${iconButton('prev', `이전 ${unit}`, 'prev')}${iconButton('next', `다음 ${unit}`, 'next')}${iconButton('search', '일정 검색', 'search')}<button class="icon-btn" data-action="today" aria-label="오늘로 이동"><span class="today-icon">${parseDate(TODAY).getDate()}</span></button>${iconButton('info', '도움말', 'help')}</header>`;
  if(planCalendarPreview)html=`<header class="toolbar calendar-toolbar view-month">${iconButton('prev','이전 달','prev')}<button class="month-title" data-action="today">${heading}</button><span class="year">${year}</span>${iconButton('next','다음 달','next')}</header>`;
  if(planTimeEdit)html='<header class="toolbar calendar-toolbar"><h2 class="plan-time-title">학습 시간 조정</h2><span class="year">변경 예정 미리보기</span></header>';
  if(normalConflictDay)html='<header class="toolbar calendar-toolbar"><h2 class="plan-time-title">충돌 일정 조정</h2><span class="year">여러 일정 수정</span></header>';
  if (searchOpen) {
    app.innerHTML = html + '<div class="search"><input id="search-input" type="search" placeholder="일정 검색" aria-label="일정 검색어"></div><div class="search-results" id="search-results"></div>';
    renderSearch(''); document.querySelector('#search-input').focus(); return;
  }
  if (calendarView === 'year') { app.innerHTML = html + renderYearView(year); return; }
  if (calendarView === 'week' || calendarView === 'day') {
    const creation=planCreation?.preview;
    app.innerHTML = html + renderTimeView(creation?{preview:true,plan:creation.plan}:{} ) + (creation?renderCreationPreviewControls():quickOverviewButton())+calendarReturnNotice();
    const visibleEvents = timeViewDates().flatMap(date=>byDate(dateKey(date)));
    const hour = visibleEvents.length ? Math.max(0, Math.floor(Math.min(...visibleEvents.map(e=>minutesOfDay(e.start))) / 60) - 1) : 8;
    const scroller = app.querySelector('.time-scroll');
    if (scroller) scroller.scrollTop = hour * 60;
    if (focusAfterRender) { app.querySelector(`[data-event="${focusAfterRender}"]`)?.focus(); focusAfterRender = null; }
    return;
  }
  html += '<div class="weekdays" aria-hidden="true">' + ['일', '월', '화', '수', '목', '금', '토'].map((d, i) => `<span class="${i === 0 ? 'sun' : i === 6 ? 'sat' : ''}">${d}</span>`).join('') + '</div>';
  html += '<div class="grid" aria-label="월간 달력">'+renderMonthCells(viewDate);
  html += `</div><section class="agenda" aria-label="선택 날짜의 일정">${renderMonthAgenda(selectedDate)}</section><button class="fab" data-action="add" aria-label="계획 또는 일정 생성" aria-expanded="false" aria-controls="fab-create-actions">+</button>`;
  app.innerHTML = html+quickOverviewButton();
  if(!planCalendarPreview && !planCreation)setMonthAgendaHeight(monthAgendaExpanded?monthAgendaLimits().max:monthAgendaLimits().min);
  mountQuickOverview();
  if (focusAfterRender) { app.querySelector(`[data-event="${focusAfterRender}"]`)?.focus(); focusAfterRender = null; }
}
function renderMonthAgenda(key){
  const date=parseDate(key),selected=byDate(key);
  return `<button type="button" class="agenda-drag-handle" data-agenda-drag="true" data-action="toggle-month-agenda" aria-label="선택 날짜 상세 일정 펼치기" aria-expanded="${monthAgendaExpanded}"><span class="handle"></span></button><div class="agenda-date" data-agenda-drag="true">${date.getDate()}일 ${['일요일', '월요일', '화요일', '수요일', '목요일', '금요일', '토요일'][date.getDay()]}<small>${date.getFullYear()}년 ${date.getMonth() + 1}월</small></div><div class="agenda-list">${selected.length ? selected.map(e => `<button class="agenda-item" data-event="${e.id}" style="--accent:${e.type === 'calendar' ? '#939499' : STATUS[e.status].color}">${escapeHTML(e.title)}${hasConflict(e) ? ' · 충돌' : ''}<small>${timeLabel(e.start)} – ${timeLabel(e.end)}</small><span class="agenda-extra">${escapeHTML(e.content)}${e.type==='study'?`<br>${STATUS[e.status].label}`:''}</span></button>`).join('') : '<p class="empty">일정이 없습니다</p>'}</div>`;
}
function renderMonthCells(target){
  const year=target.getFullYear(),month=target.getMonth();
  const first = new Date(year, month, 1, 12), days = new Date(year, month + 1, 0).getDate();
  const count = Math.ceil((first.getDay() + days) / 7) * 7;
  let html='';
  for (let i = 0; i < count; i++) {
    const day = new Date(year, month, i - first.getDay() + 1, 12), key = dateKey(day);
    const dayItems=byDate(key),shown=planCalendarPreview?dayItems:dayItems.slice(0,5);
    const exams=activeExamsOn(key),examLabel=exams.length?', '+exams.map(plan=>plan.name).join(', ')+' 시험일':'';
    html += `<div data-drop-date="${key}" class="day ${exams.length?'exam-day':''} ${day.getMonth() !== month ? 'outside' : ''}"><button class="date-button ${i % 7 === 0 ? 'sun' : i % 7 === 6 ? 'sat' : ''} ${key === TODAY ? 'today' : ''} ${key === selectedDate ? 'selected' : ''}" data-date="${key}" aria-label="${longDate(key)}${escapeHTML(examLabel)}" ${key === TODAY ? 'aria-current="date"' : ''} ${key === selectedDate ? 'aria-pressed="true"' : 'aria-pressed="false"'}>${day.getDate()}</button>${exams.map(plan=>examDateButton(plan,plan.examLabel || (plan.id==='sqld'?'SQLD 시험':'기사 시험일'))).join('')}${shown.map((event,index)=>{const markup=eventButton(event);return !planCalendarPreview && index>=3?markup.replace('class="event ', 'class="event month-extra-event '):markup;}).join('')}${!planCalendarPreview && dayItems.length > 3 ? `<button class="event month-more-normal" style="background:#f3f3f3;color:#777" data-date="${key}">+${dayItems.length - 3}개 더</button>${dayItems.length>5?`<button type="button" class="month-more-compact" data-date="${key}" aria-label="${longDate(key)} 숨겨진 일정 ${dayItems.length-5}개 더 보기">+${dayItems.length-5}</button>`:''}` : ''}</div>`;
  }
  return html;
}
const VIEW_LABELS = {year:'년',month:'월',week:'주',day:'일'};
function activeExamsOn(date) { const draft=planCreation?.reschedulePlanId && planCreation.preview?.plan;return plans.map(plan=>draft?.id===plan.id?draft:plan).filter(plan=>plan.active && plan.examDate===date); }
function examDateButton(plan,label) {
  const replan=planCreation?.reschedulePlanId===plan.id && planCreation?.preview;
  const focused=replan?.examSpotlight?.kind==='after';
  const readOnly=Boolean(planCreation?.preview && plan.id!==planCreation.preview.plan.id);
  return `<button type="button" class="exam-day-tag exam-date-button ${focused?'replan-exam-date-highlight':''}${readOnly?' creation-preview-readonly':''}" ${readOnly?'disabled aria-disabled="true" tabindex="-1"':''} ${replan?'data-replan-exam-marker="after"':''} data-edit-exam="${plan.id}" aria-label="${escapeHTML(plan.name)} 시험일 변경" title="시험일 변경">${escapeHTML(label)}</button>`;
}
function renderYearView(year) {
  return `<section class="year-view" aria-label="${year}년 연간 달력">${Array.from({length:12},(_,month)=>{
    const first = new Date(year,month,1,12), count = new Date(year,month+1,0).getDate();
    const monthKey = dateKey(first);
    return `<section class="mini-month"><button class="mini-month-title" data-mini-month="${monthKey}" aria-label="${year}년 ${month+1}월 보기">${month+1}월</button><div class="mini-weekdays" aria-hidden="true">${['일','월','화','수','목','금','토'].map(d=>`<span>${d}</span>`).join('')}</div><div class="mini-grid">${Array.from({length:first.getDay()},()=>'<span></span>').join('')}${Array.from({length:count},(_,index)=>{
      const day = new Date(year,month,index+1,12), key=dateKey(day), exam=activeExamsOn(key).length>0;
      return `<button class="mini-date ${day.getDay()===0?'sun':day.getDay()===6?'sat':''} ${key===TODAY?'mini-today':''} ${exam?'mini-exam':''} ${byDate(key).length?'mini-has-events':''}" data-mini-date="${key}" aria-label="${longDate(key)}${exam?', 시험일':''}">${index+1}</button>`;
    }).join('')}</div></section>`;
  }).join('')}</section>`;
}
function timeViewDates(target=viewDate) {
  const first = new Date(target);
  if (calendarView === 'week') first.setDate(first.getDate()-first.getDay());
  return Array.from({length:calendarView==='week'?7:1},(_,i)=>{const date=new Date(first);date.setDate(date.getDate()+i);return date;});
}
function timeEventLayout(items) {
  const sorted = [...items].sort((a,b)=>a.start.localeCompare(b.start)||a.end.localeCompare(b.end));
  const result=[];let group=[], groupEnd=-1;
  function flush() {
    const lanes=[];
    const arranged=group.map(e=>{
      let lane=lanes.findIndex(end=>end<=minutesOfDay(e.start));
      if(lane<0)lane=lanes.length;
      lanes[lane]=minutesOfDay(e.end);return {event:e,lane};
    });
    arranged.forEach(item=>result.push({...item,lanes:lanes.length}));group=[];
  }
  sorted.forEach(e=>{if(group.length&&minutesOfDay(e.start)>=groupEnd){flush();groupEnd=-1;}group.push(e);groupEnd=Math.max(groupEnd,minutesOfDay(e.end));});
  flush();return result;
}
function replanOriginalEvents(){
  const preview=planCreation?.reschedulePlanId && planCreation.preview;
  return preview?events.filter(e=>preview.replaceIds.has(e.id)).map(e=>({...e,id:'replan-original-'+e.id,isReplacementOriginal:true})):[];
}
function renderOriginalReplanExam(date){
  const draft=planCreation,preview=draft?.reschedulePlanId && draft.preview;
  if(!preview || draft.originalExamDate===preview.plan.examDate || date!==draft.originalExamDate)return '';
  return '<span data-replan-exam-marker="before" class="exam-day-tag replan-old-exam '+(preview.examSpotlight?.kind==='before'?'replan-exam-date-highlight':'')+'" aria-label="변경 전 시험일 '+longDate(date)+'">변경 전 시험일</span>';
}
let timelineScale=1;
let timelineZoomGesture=null,timelineZoomFrame=null,timelineZoomTarget=null;
function applyTimelineZoom(scale,anchor){
  timelineScale=scale;
  const canvas=app.querySelector('.time-canvas'),scroll=app.querySelector('.time-scroll');if(!canvas || !scroll)return;
  canvas.style.setProperty('--time-scale',scale);
  canvas.querySelectorAll('[data-time-minute]').forEach(node=>{
    node.style.top=`${Number(node.dataset.timeMinute)*scale}px`;
    if(node.dataset.timeDuration!==undefined)node.style.height=`${Math.max(node.classList.contains('timed-event')?28:0,Number(node.dataset.timeDuration)*scale-(node.classList.contains('timed-event')?2:0))}px`;
  });
  scroll.scrollTop=Math.max(0,anchor.minute*scale-anchor.offset);
  const output=app.querySelector('.timeline-zoom output');if(output)output.textContent=`${Math.round(scale*100)}%`;
}
function animateTimelineZoom(){
  timelineZoomFrame=null;const target=timelineZoomTarget;if(!target)return;
  if(!app.querySelector('.time-canvas') || !['week','day'].includes(calendarView)){timelineZoomTarget=null;return;}
  const remaining=target.scale-timelineScale;
  const next=Math.abs(remaining)<.001 || window.matchMedia('(prefers-reduced-motion: reduce)').matches?target.scale:timelineScale+remaining*.3;
  applyTimelineZoom(next,target.anchor);
  if(next!==target.scale)timelineZoomFrame=requestAnimationFrame(animateTimelineZoom);
  else timelineZoomTarget=null;
}
function beginTimelineZoom(event){
  if(event.touches.length!==2 || !event.target.closest('.time-scroll') || pendingMove || selectedEvent || !['week','day'].includes(calendarView))return;
  event.preventDefault();
  endCalendarSwipe(null,true);clearPointerMove();
  const [a,b]=event.touches,scroll=app.querySelector('.time-scroll'),offset=(a.clientY+b.clientY)/2-scroll.getBoundingClientRect().top;
  timelineZoomGesture={distance:Math.max(10,Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY)),scale:timelineScale,anchor:{minute:(scroll.scrollTop+offset)/timelineScale,offset}};
}
function moveTimelineZoom(event){
  const gesture=timelineZoomGesture;if(!gesture || event.touches.length!==2)return;
  event.preventDefault();const [a,b]=event.touches;
  timelineZoomTarget={scale:Math.max(.5,Math.min(2,gesture.scale*Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY)/gesture.distance)),anchor:gesture.anchor};
  if(timelineZoomFrame===null)timelineZoomFrame=requestAnimationFrame(animateTimelineZoom);
}
function endTimelineZoom(event,cancel=false){
  const gesture=timelineZoomGesture;if(!gesture)return;
  if(cancel){timelineZoomTarget=null;if(timelineZoomFrame!==null)cancelAnimationFrame(timelineZoomFrame);timelineZoomFrame=null;applyTimelineZoom(gesture.scale,gesture.anchor);}
  timelineZoomGesture=null;suppressEventClickUntil=Date.now()+400;
}
app.addEventListener('touchstart',beginTimelineZoom,{passive:false,capture:true});
app.addEventListener('touchmove',moveTimelineZoom,{passive:false,capture:true});
app.addEventListener('touchend',event=>{if(timelineZoomGesture && event.touches.length<2)endTimelineZoom(event);},{passive:true,capture:true});
app.addEventListener('touchcancel',event=>endTimelineZoom(event,true),{passive:true,capture:true});
function setTimelineScale(value){
  if(pointerMove || !['week','day'].includes(calendarView))return;
  const next=Math.max(.5,Math.min(2,Math.round(value*100)/100));if(next===timelineScale)return;
  const scroll=app.querySelector('.time-scroll'),center=((scroll?.scrollTop || 0)+(scroll?.clientHeight || 0)/2)/timelineScale;
  timelineScale=next;renderCalendar();
  const current=app.querySelector('.time-scroll');if(current)current.scrollTop=Math.max(0,center*next-(current.clientHeight || 0)/2);
}
function renderTimeView(options={}) {
  const dates=options.dates || timeViewDates(), isWeek=calendarView==='week';
  const start=dates[0],end=dates.at(-1);
  const caption=isWeek?`${start.getMonth()+1}월 ${start.getDate()}일 – ${end.getMonth()+1}월 ${end.getDate()}일`:longDate(dateKey(start));
  const headings=dates.map(date=>{const key=dateKey(date);return `<button class="time-day-heading ${planCreation?.preview?.examSpotlight?.date===key?'replan-exam-date-highlight':''} ${key===TODAY?'time-today':''} ${date.getDay()===0?'sun':date.getDay()===6?'sat':''}" data-open-day="${key}" aria-label="${longDate(key)} 일 보기"><small>${['일','월','화','수','목','금','토'][date.getDay()]}</small><b>${date.getDate()}</b></button>`;}).join('');
  const examRows=dates.map(date=>`<div class="time-exam-cell">${renderOriginalReplanExam(dateKey(date))}${[...activeExamsOn(dateKey(date)),...(options.plan?.examDate===dateKey(date) && !activeExamsOn(dateKey(date)).some(plan=>plan.id===options.plan.id)?[options.plan]:[])].map(plan=>examDateButton(plan,isWeek?(plan.examLabel || (plan.id==='sqld'?'SQLD 시험':'기사 시험')):plan.name+' 시험일')).join('')}</div>`).join('');
  const cols=dates.map(date=>{const key=dateKey(date);return `<div class="time-day-column" data-drop-date="${key}" aria-label="${longDate(key)} 일정">${options.preview?renderCreationPreferenceBackground(date):''}${timeEventLayout([...byDate(key),...replanOriginalEvents().filter(e=>e.date===key)]).map(({event:e,lane,lanes})=>{
    const top=minutesOfDay(e.start)*timelineScale,height=Math.max(28,(minutesOfDay(e.end)-minutesOfDay(e.start))*timelineScale-2),left=lane/lanes*100,width=100/lanes;
    const inset=options.preview?4:1;
    return eventButton(e).replace('class="event ', 'class="event timed-event ').replace('data-event=', `data-time-minute="${minutesOfDay(e.start)}" data-time-duration="${minutesOfDay(e.end)-minutesOfDay(e.start)}" style="top:${top}px;height:${height}px;left:calc(${left}% + ${inset}px);width:calc(${width}% - ${inset*2}px)" data-event=`).replace('</button>',`<span class="timed-event-time">${e.start}–${e.end}</span></button>`);
  }).join('')}${key===TODAY?`<div class="time-now" data-time-minute="870" style="top:${870*timelineScale}px" aria-label="예시 현재 시간 오후 2시 30분"></div>`:''}</div>`;}).join('');
  return `<section class="time-view ${isWeek?'week-view':'day-view'}" aria-label="${isWeek?'주간':'일간'} 달력"><div class="time-caption"><span>${caption}</span><div class="timeline-zoom"><span>두 손가락으로 확대·축소</span><output>${Math.round(timelineScale*100)}%</output></div></div><div class="time-head" style="--days:${dates.length}"><span></span>${headings}</div><div class="time-exams" style="--days:${dates.length}"><span class="all-day-label">종일</span>${examRows}</div><div class="time-scroll"><div class="time-canvas" style="--days:${dates.length};--time-scale:${timelineScale}"><div class="time-hours" data-time-zoom-surface="true" title="두 손가락으로 벌리거나 오므려 세로 확대·축소" aria-label="시간 눈금">${Array.from({length:24},(_,hour)=>`<span data-time-minute="${hour*60}" style="top:${hour*60*timelineScale}px">${hour===0?'오전 12':hour===12?'오후 12':hour<12?hour:hour-12}</span>`).join('')}</div>${cols}</div></div></section>${options.preview?'':'<button class="fab" data-action="add" aria-label="계획 또는 일정 생성" aria-expanded="false" aria-controls="fab-create-actions">+</button>'}`;
}
function shiftedPage(date,direction,mode=calendarView) {
  const next=new Date(date);
  if(mode==='year') {next.setDate(1);next.setFullYear(next.getFullYear()+direction);}
  else if(mode==='month') {next.setDate(1);next.setMonth(next.getMonth()+direction);}
  else next.setDate(next.getDate()+direction*(mode==='week'?7:1));
  return next;
}
function navigateCalendar(direction) {
  const next=shiftedPage(viewDate,direction);
  viewDate=next;selectedDate=dateKey(next);renderCalendar();
}
let calendarSwipe=null,calendarSwipeMotion=null;
function beginCalendarSwipe(event){
  if(event.button!==0 || event.isPrimary===false || !['month','week','day'].includes(calendarView) || selectedEvent || selectedPlan || searchOpen || pendingMove || pointerMove || planCreation || planCalendarPreview || normalConflictDay || document.querySelector('.phone').classList.contains('popup-interaction-locked'))return false;
  const agenda=calendarView==='month' && event.target.closest('.agenda');
  if(agenda){
    if(event.target.closest('[data-agenda-drag],input,select,textarea,a'))return false;
  }else if(event.target.closest('button,input,select,textarea,a,[data-agenda-drag]') || !event.target.closest('.grid,.time-scroll'))return false;
  calendarSwipeMotion?.finish();dragPageSlide?.finish();
  calendarSwipe={pointerId:event.pointerId,x:event.clientX,y:event.clientY,active:false,agenda:Boolean(agenda)};return true;
}
function moveCalendarSwipe(event){
  const swipe=calendarSwipe;if(!swipe || swipe.pointerId!==event.pointerId)return;
  const dx=event.clientX-swipe.x,dy=event.clientY-swipe.y;
  if(!swipe.active){
    if(Math.max(Math.abs(dx),Math.abs(dy))<10)return;
    if(Math.abs(dx)<=Math.abs(dy)*1.2){calendarSwipe=null;return;}
    swipe.active=true;app.setPointerCapture(event.pointerId);
    swipe.surface=app.querySelector(swipe.agenda?'.agenda':calendarView==='month'?'.grid':'.time-view');
    swipe.originalTransform=swipe.surface.style.transform || '';
    swipe.originalWillChange=swipe.surface.style.willChange || '';
    swipe.surface.style.willChange='transform';
    prepareCalendarSwipeNeighbors(swipe);
  }
  swipe.offset=dx;
  swipe.surface.style.transform=`translateX(${dx}px)`;
  swipe.neighbors?.forEach(({page,direction})=>{page.style.transform=`translateX(calc(${direction*100}% + ${dx}px))`;});
  event.preventDefault();
}
function prepareCalendarSwipeNeighbors(swipe){
  const surface=swipe.surface,phone=document.querySelector('.phone');
  if(!surface.cloneNode || !surface.getBoundingClientRect || !phone.getBoundingClientRect)return;
  const rect=surface.getBoundingClientRect(),frame=phone.getBoundingClientRect();
  const overlay=document.createElement('div');
  overlay.className='drag-page-slide calendar-swipe-neighbors';overlay.inert=true;overlay.setAttribute('aria-hidden','true');
  overlay.style.cssText=`left:${rect.left-frame.left}px;top:${rect.top-frame.top}px;width:${rect.width}px;height:${rect.height}px;background:transparent`;
  swipe.neighbors=[-1,1].map(direction=>{
    const target=swipe.agenda?shiftedPage(parseDate(selectedDate),direction,'day'):shiftedPage(viewDate,direction);
    let page;
    if(swipe.agenda || calendarView==='month'){
      page=cloneDragPage(surface);page.innerHTML=swipe.agenda?renderMonthAgenda(dateKey(target)):renderMonthCells(target);
    }else{
      const container=document.createElement('div');container.innerHTML=renderTimeView({dates:timeViewDates(target)});
      page=cloneDragPage(container.querySelector('.time-view'));
    }
    page.style.background='#fff';page.style.transform=`translateX(${direction*100}%)`;
    overlay.append(page);return {page,direction};
  });
  phone.append(overlay);swipe.neighborOverlay=overlay;
  swipe.neighbors.forEach(({page})=>{const scroll=page.querySelector('.time-scroll');if(scroll)scroll.scrollTop=surface.querySelector('.time-scroll')?.scrollTop || 0;});
}
function restoreCalendarSwipe(swipe){
  if(!swipe.surface)return;
  swipe.surface.style.transform=swipe.originalTransform;
  swipe.surface.style.willChange=swipe.originalWillChange;
  swipe.neighborOverlay?.remove();
}
function settleCalendarSwipe(swipe){
  const surface=swipe.surface;
  if(!surface?.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches){restoreCalendarSwipe(swipe);return;}
  const options={duration:260,easing:'cubic-bezier(.22,.75,.18,1)',fill:'both'};
  const animations=[surface.animate([{transform:`translateX(${swipe.offset || 0}px)`},{transform:swipe.originalTransform || 'translateX(0)'}],options),...(swipe.neighbors || []).map(({page,direction})=>page.animate([{transform:`translateX(calc(${direction*100}% + ${swipe.offset || 0}px))`},{transform:`translateX(${direction*100}%)`}],options))];
  let finished=false;
  const motion={finish(){if(finished)return;finished=true;restoreCalendarSwipe(swipe);animations.forEach(animation=>animation.cancel());if(calendarSwipeMotion===motion)calendarSwipeMotion=null;}};
  calendarSwipeMotion=motion;Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(()=>motion.finish());
}
function endCalendarSwipe(event,cancel=false){
  const swipe=calendarSwipe;if(!swipe || (event && swipe.pointerId!==event.pointerId))return;
  calendarSwipe=null;
  if(app.hasPointerCapture(swipe.pointerId))app.releasePointerCapture(swipe.pointerId);
  if(!swipe.active)return;
  event?.preventDefault?.();suppressEventClickUntil=Date.now()+400;
  const dx=cancel?(swipe.offset || 0):event.clientX-swipe.x;
  swipe.offset=dx;
  if(cancel || Math.abs(dx)<50){settleCalendarSwipe(swipe);return;}
  const direction=dx<0?1:-1,scroll=app.querySelector('.time-scroll')?.scrollTop;
  const selector=swipe.agenda?'.agenda':null;
  dragPageSlide?.finish();const outgoing=captureDragPage(selector);
  restoreCalendarSwipe(swipe);
  if(swipe.agenda){selectedDate=dateKey(shiftedPage(parseDate(selectedDate),direction,'day'));viewDate=parseDate(selectedDate);renderCalendar();}
  else navigateCalendar(direction);
  if(calendarView!=='month' && Number.isFinite(scroll))app.querySelector('.time-scroll').scrollTop=scroll;
  if(outgoing)animateDragPage(outgoing,direction,dx,selector);
}
let pendingMove = null, pointerMove = null, keyboardMove = null, liveMove = null, suppressEventClickUntil = 0;
let moveClosing = false, moveClosePromise = null;
let movePicking = false;
let movePreviewScrollFrame = null;
let dragPageSlide = null;
let moveDetailsCollapsed=false,sheetGesture=null,sheetClickSuppressedUntil=0,sheetResizeMotion=null;
let monthAgendaExpanded=false,monthAgendaGesture=null,monthAgendaMotion=null,monthAgendaClickUntil=0,monthAgendaContentMotion=null;
function clearMonthAgendaContentMotion(){monthAgendaContentMotion?.finish();}
function captureMonthAgendaContent(){
  clearMonthAgendaContentMotion();
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches)return null;
  const sheet=app.querySelector('.agenda'),frame=sheet?.getBoundingClientRect?.();
  if(!frame)return null;
  const parts=['.agenda-date','.agenda-list'].map(selector=>{
    const node=app.querySelector(selector);if(!node?.animate || !node.cloneNode || !node.getBoundingClientRect)return null;
    return {selector,ghost:node.cloneNode(true),rect:node.getBoundingClientRect(),scrollTop:node.scrollTop || 0};
  }).filter(Boolean);
  return parts.length?{frame,parts}:null;
}
function animateMonthAgendaContent(snapshot){
  if(!snapshot)return;
  const sheet=app.querySelector('.agenda'),animations=[],ghosts=[];
  const options={duration:240,easing:'cubic-bezier(.22,.75,.18,1)',fill:'both'};
  snapshot.parts.forEach(({selector,ghost,rect,scrollTop})=>{
    const incoming=app.querySelector(selector);if(!incoming?.animate)return;
    ghost.classList.add('agenda-content-ghost');ghost.inert=true;ghost.setAttribute('aria-hidden','true');
    Object.assign(ghost.style,{position:'absolute',left:`${rect.left-snapshot.frame.left}px`,top:`${rect.top-snapshot.frame.top}px`,width:`${rect.width}px`,height:`${rect.height}px`,margin:'0',pointerEvents:'none',zIndex:'2'});
    sheet.append(ghost);ghost.scrollTop=scrollTop;ghosts.push(ghost);
    animations.push(ghost.animate([{opacity:1,transform:'translateY(0)'},{opacity:0,transform:'translateY(-4px)'}],options),incoming.animate([{opacity:0,transform:'translateY(6px)'},{opacity:1,transform:'translateY(0)'}],options));
  });
  if(!animations.length)return;
  let finished=false;
  const motion={finish(){if(finished)return;finished=true;animations.forEach(animation=>animation.cancel());ghosts.forEach(ghost=>ghost.remove());if(monthAgendaContentMotion===motion)monthAgendaContentMotion=null;}};
  monthAgendaContentMotion=motion;Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(()=>motion.finish());
}
function monthAgendaLimits(){
  const phone=document.querySelector('.phone'),narrow=window.matchMedia('(max-width:710px)').matches;
  return {min:narrow?165:153,max:phone.clientHeight/2,available:phone.clientHeight-155-(narrow?12:24)};
}
function setMonthAgendaHeight(height){
  const phone=document.querySelector('.phone'),limits=monthAgendaLimits();
  phone.classList.add('month-agenda-sized');phone.classList.toggle('month-agenda-expanded',height>(limits.min+limits.max)/2);
  phone.style.setProperty('--month-agenda-height',`${height}px`);phone.style.setProperty('--month-agenda-grid-height',`${Math.max(130,limits.available-height)}px`);
  const handle=app.querySelector('.agenda-drag-handle');handle?.setAttribute('aria-expanded',String(height>(limits.min+limits.max)/2));handle?.setAttribute('aria-label',height>(limits.min+limits.max)/2?'선택 날짜 상세 일정 접기':'선택 날짜 상세 일정 펼치기');
}
function stopMonthAgendaMotion(){
  const motion=monthAgendaMotion;monthAgendaMotion=null;motion?.forEach(animation=>animation.cancel());
  document.querySelector('.phone').classList.remove('month-agenda-settling');
}
function setMonthAgendaExpanded(expanded,fromGeometry=null){
  if(calendarView!=='month' || planCalendarPreview || planCreation || pendingMove)return;
  const sheet=app.querySelector('.agenda'),grid=app.querySelector('.grid'),from=fromGeometry?.sheet??sheet?.getBoundingClientRect?.().height,gridFrom=fromGeometry?.grid??grid?.getBoundingClientRect?.().height;
  stopMonthAgendaMotion();monthAgendaExpanded=expanded;
  const limits=monthAgendaLimits(),height=expanded?limits.max:limits.min;
  setMonthAgendaHeight(height);
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches || !sheet?.animate || !grid?.animate || !Number.isFinite(from) || !Number.isFinite(gridFrom))return;
  const options={duration:320,easing:'cubic-bezier(.22,.75,.18,1)',fill:'both'};
  document.querySelector('.phone').classList.add('month-agenda-settling');
  const animations=[sheet.animate([{height:`${from}px`},{height:`${height}px`}],options),grid.animate([{height:`${gridFrom}px`},{height:`${Math.max(130,limits.available-height)}px`}],options)];monthAgendaMotion=animations;
  Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(()=>{if(monthAgendaMotion===animations)stopMonthAgendaMotion();});
}
function beginMonthAgendaGesture(event){
  if(event.button!==0 || calendarView!=='month' || selectedEvent || selectedPlan || pointerMove || pendingMove || planCalendarPreview || planCreation || monthAgendaGesture)return;
  const limits=monthAgendaLimits(),height=app.querySelector('.agenda')?.getBoundingClientRect?.().height || (monthAgendaExpanded?limits.max:limits.min);
  stopMonthAgendaMotion();monthAgendaGesture={pointerId:event.pointerId,y:event.clientY,lastY:event.clientY,height,expanded:monthAgendaExpanded,moved:false};
  app.setPointerCapture(event.pointerId);event.preventDefault();
}
function moveMonthAgendaGesture(event){
  const gesture=monthAgendaGesture;if(!gesture || gesture.pointerId!==event.pointerId)return;
  gesture.lastY=event.clientY;if(Math.abs(event.clientY-gesture.y)<6 && !gesture.moved)return;
  gesture.moved=true;event.preventDefault();document.querySelector('.phone').classList.add('month-agenda-dragging');
  const limits=monthAgendaLimits();setMonthAgendaHeight(Math.max(limits.min,Math.min(limits.max,gesture.height+gesture.y-event.clientY)));
}
function endMonthAgendaGesture(event,cancel=false){
  const gesture=monthAgendaGesture;if(!gesture || (event && gesture.pointerId!==event.pointerId))return;
  monthAgendaGesture=null;document.querySelector('.phone').classList.remove('month-agenda-dragging');
  if(app.hasPointerCapture(gesture.pointerId))app.releasePointerCapture(gesture.pointerId);
  if(cancel || gesture.moved){monthAgendaClickUntil=Date.now()+400;setMonthAgendaExpanded(cancel?gesture.expanded:gesture.lastY<gesture.y);}
}
function sheetGeometry(kind) {
  const sheet=document.querySelector(kind==='move'?'.move-sheet':'#plan-adjustment-preview');
  const surface=app.querySelector(kind==='move' && calendarView!=='month'?'.time-view':'.grid');
  return {sheet,surface,sheetHeight:sheet.getBoundingClientRect?.().height,surfaceHeight:surface.getBoundingClientRect?.().height};
}
function stopSheetResizeMotion() {
  const motion=sheetResizeMotion;sheetResizeMotion=null;
  motion?.animations.forEach(animation=>animation.cancel());
  document.querySelector('.phone').classList.remove('sheet-settling');
}
function settleSheetGeometry(kind,expanded,from) {
  const phone=document.querySelector('.phone');
  const timeline=kind==='move' && calendarView!=='month';
  const sheetHeight=expanded?(timeline?232:phone.clientHeight/2):(kind==='move'?176:164);
  const surfaceHeight=Math.max(kind==='plan' && !expanded?180:130,phone.clientHeight-(timeline?120:155)-sheetHeight);
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches || !from.sheet.animate || !from.surface.animate || !Number.isFinite(from.sheetHeight) || !Number.isFinite(from.surfaceHeight))return;
  const options={duration:320,easing:'cubic-bezier(.22,.75,.18,1)',fill:'forwards'};
  const animations=[
    from.sheet.animate([{height:`${from.sheetHeight}px`},{height:`${sheetHeight}px`}],options),
    from.surface.animate([{height:`${from.surfaceHeight}px`},{height:`${surfaceHeight}px`}],options)
  ];
  const motion={animations,finished:null};sheetResizeMotion=motion;
  motion.finished=Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(()=>{
    if(sheetResizeMotion!==motion)return;
    stopSheetResizeMotion();
  });
  return motion.finished;
}
function updateMoveSheetSize(timeline,dragging) {
  const phone=document.querySelector('.phone');
  const height=moveDetailsCollapsed?176:timeline?(dragging?104:232):phone.clientHeight/2;
  phone.style.setProperty('--move-grid-height',`${Math.max(130,phone.clientHeight-(timeline?120:155)-height)}px`);
}
function setSheetExpanded(kind,expanded,from=sheetGeometry(kind)) {
  if(moveClosing || planAdjustmentExit)return;
  if(kind==='plan' && (!planCalendarPreview || planTimeEdit))return;
  stopSheetResizeMotion();
  from.sheet.classList.add('sheet-interacted');
  document.querySelector('.phone').classList.add('sheet-settling');
  if(kind==='move'){
    moveDetailsCollapsed=!expanded;
    document.querySelector('#move-preview').classList.toggle('details-collapsed',!expanded);
    updateMoveSheetSize(calendarView==='week'||calendarView==='day',document.querySelector('#move-preview').classList.contains('dragging'));
  }else{
    if(expanded){
      showPlanDragDetails(null,selectedDate);
      const phone=document.querySelector('.phone');
      phone.style.setProperty('--plan-drag-grid-height',`${Math.max(130,phone.clientHeight/2-155)}px`);
    }else hidePlanDragDetails();
  }
  const button=document.querySelector(kind==='move'?'#toggle-move-details':'#toggle-plan-details');
  button.setAttribute('aria-expanded',String(expanded));
  button.setAttribute('aria-label',expanded?'상세 일정 접기':'상세 일정 펼치기');
  const transition=settleSheetGeometry(kind,expanded,from);
  if(!transition)document.querySelector('.phone').classList.remove('sheet-settling');
  return transition;
}
function beginSheetGesture(kind,event) {
  if(event.button!==0 || pointerMove || sheetGesture || moveClosing || planAdjustmentExit || (kind==='plan' && planTimeEdit))return;
  event.preventDefault();
  const phone=document.querySelector('.phone'),sheet=document.querySelector(kind==='move'?'.move-sheet':'#plan-adjustment-preview');
  const expanded=kind==='move'?!moveDetailsCollapsed:!document.querySelector('#plan-drag-details').hidden;
  const min=kind==='move'?176:164,max=kind==='move' && calendarView!=='month'?232:phone.clientHeight/2;
  const height=sheet.getBoundingClientRect?.().height || (expanded?max:min);
  stopSheetResizeMotion();sheet.classList.add('sheet-interacted');
  sheetGesture={kind,sheet,source:event.currentTarget,pointerId:event.pointerId,startY:event.clientY,lastY:event.clientY,expanded,min,max,height,moved:false};
  event.currentTarget.setPointerCapture?.(event.pointerId);
}
function moveSheetGesture(event) {
  const gesture=sheetGesture;
  if(!gesture || gesture.pointerId!==event.pointerId)return;
  event.preventDefault();gesture.lastY=event.clientY;
  const delta=event.clientY-gesture.startY;
  if(Math.abs(delta)<6 && !gesture.moved)return;
  if(!gesture.moved){
    gesture.moved=true;
    if(gesture.kind==='plan' && !gesture.expanded)showPlanDragDetails(null,selectedDate);
  }
  const height=Math.max(gesture.min,Math.min(gesture.max,gesture.height-delta));
  const phone=document.querySelector('.phone');phone.classList.add('sheet-resizing');
  gesture.sheet.style.height=`${height}px`;
  const gridHeight=`${Math.max(130,phone.clientHeight-(gesture.kind==='move' && calendarView!=='month'?120:155)-height)}px`;
  phone.style.setProperty(gesture.kind==='move'?'--move-grid-height':'--plan-drag-grid-height',gridHeight);
}
function endSheetGesture(event,cancel=false) {
  const gesture=sheetGesture;
  if(!gesture || (event && event.pointerId!==gesture.pointerId))return;
  const from=sheetGeometry(gesture.kind);
  sheetGesture=null;
  if(gesture.source.hasPointerCapture?.(gesture.pointerId))gesture.source.releasePointerCapture(gesture.pointerId);
  gesture.sheet.style.height='';
  document.querySelector('.phone').classList.remove('sheet-resizing');
  if(gesture.moved){
    sheetClickSuppressedUntil=Date.now()+400;
    const delta=gesture.lastY-gesture.startY;
    return setSheetExpanded(gesture.kind,cancel?gesture.expanded:Math.abs(delta)>=28?delta<0:gesture.height-delta>(gesture.min+gesture.max)/2,from);
  }
}
function cancelSheetGesture(){endSheetGesture(null,true);}
for(const [kind,handle,heading] of [['move','#toggle-move-details','#move-sheet-header'],['plan','#toggle-plan-details','#plan-calendar-heading']]){
  for(const selector of [handle,heading])document.querySelector(selector).addEventListener('pointerdown',event=>beginSheetGesture(kind,event));
  document.querySelector(handle).addEventListener('click',()=>{
    if(Date.now()<sheetClickSuppressedUntil || pointerMove || sheetGesture)return;
    const expanded=kind==='move'?!moveDetailsCollapsed:!document.querySelector('#plan-drag-details').hidden;
    setSheetExpanded(kind,!expanded);
  });
}
function moveSchedule(e, date, times) {
  const existing = pendingMove?.id === e.id ? pendingMove : null;
  return {date, start:times?.start ?? existing?.start ?? e.start, end:times?.end ?? existing?.end ?? e.end};
}
function openMovePreview(id, targetDate, times = null) {
  if (moveClosing) return false;
  const e = movePreviewEvent(id);
  if (!canMoveEvent(e)) return false;
  const schedule = moveSchedule(e, targetDate, times);
  if (!validSchedule(schedule)) return false;
  if (!canMoveToDate(e, targetDate)) { showBlockedMovePreview(e, targetDate, schedule); return true; }
  const previous = pendingMove;
  pendingMove = {id, ...schedule, batchDraft:Boolean(previous?.batchDraft), creationDraft:Boolean(planCreation?.preview), returnToDetail:Boolean(previous?.returnToDetail), returnToMonth:Boolean(previous?.returnToMonth), autoSuggested:Boolean(previous?.autoSuggested), timed:Boolean(times || previous?.timed || calendarView==='week' || calendarView==='day'), before:{date:e.date, start:e.start, end:e.end}, selectedDate:previous?.selectedDate ?? selectedDate, viewDate:previous?.viewDate ?? new Date(viewDate)};
  movePicking = false; keyboardMove = null;
  selectedDate = targetDate; viewDate = parseDate(targetDate);
  // Keep the live grid in place so dropping does not interrupt its size transition.
  if (!liveMove || !app.querySelector(`[data-drop-date="${targetDate}"]`)) renderCalendar();
  paintMovePreview(e, targetDate, false, schedule);
  app.inert = false;
  document.querySelector('#cancel-move').focus({preventScroll:true});
  return true;
}
function movePreviewEvent(id) {
  return calendarEvents().find(e=>e.id===id);
}
function openPlanDraftMove(id,date,times,originViewDate,originSelectedDate) {
  const previous=pendingMove;
  hidePlanDragDetails();
  pendingMove={id,batchDraft:true,selectedDate:previous?.selectedDate ?? originSelectedDate ?? selectedDate,viewDate:previous?.viewDate ?? originViewDate ?? new Date(viewDate)};
  document.querySelector('.phone').classList.add('plan-moving');
  if(!openMovePreview(id,date,times)){
    pendingMove=null;document.querySelector('.phone').classList.remove('plan-moving');renderCalendar();
  }
}
function finishPlanDraftMove(move,apply) {
  document.querySelector('.phone').classList.remove('plan-moving');
  document.querySelector('.phone').classList.remove('plan-returning');
  if(planTimeEdit){
    if(apply)planTimeEdit.draft={date:move.date,start:move.start,end:move.end};
    finishPlanTimeEdit(apply);
    if(!window.matchMedia('(prefers-reduced-motion: reduce)').matches)app.animate?.([{opacity:.65},{opacity:1}],{duration:180,easing:'ease-out'});
    return;
  }
  let dateChanged=false;
  if(apply){
    const item=planCalendarPreview.items.find(item=>item.id===move.id);
    dateChanged=item.after.date!==move.date;
    item.after={date:move.date,start:move.start,end:move.end};item.manuallyAdjusted=true;
    selectedDate=move.date;viewDate=parseDate(move.date);
  }
  calendarView='month';renderCalendar();updatePlanCalendarSummary();
  if(apply && dateChanged){
    const e=movePreviewEvent(move.id);
    if(hasConflict(e))offerTimeAdjustment(e);
  }
}
function closePlanDragPreview() {
  return closeMovePreview().then(()=>{
    document.querySelector('.phone').classList.remove('plan-moving');
    document.querySelector('.phone').classList.remove('plan-returning');
    if(planCalendarPreview){renderCalendar();updatePlanCalendarSummary();}
  });
}
function showBlockedMovePreview(e, targetDate, times = null) {
  if (!pendingMove) pendingMove = {id:e.id, date:targetDate, before:{date:e.date, start:e.start, end:e.end}, selectedDate, viewDate:new Date(viewDate)};
  pendingMove.date = targetDate;
  Object.assign(pendingMove, moveSchedule(e,targetDate,times));
  pendingMove.timed = Boolean(times || pendingMove.timed || calendarView==='week' || calendarView==='day');
  pendingMove.blockedDate = targetDate;
  movePicking = true;
  keyboardMove = {id:e.id, date:targetDate};
  paintMovePreview(e, targetDate, false, pendingMove);
  app.inert = false;
  markDropTarget(null);
  app.querySelector(`[data-drop-date="${targetDate}"]`)?.classList.add('drop-blocked');
  app.querySelector('[data-reposition]')?.focus({preventScroll:true});
}
function paintMovePreview(e, targetDate, dragging, times = null) {
  const restriction = moveRestriction(e, targetDate);
  const phone = document.querySelector('.phone');
  const moveLayer=document.querySelector('#move-preview');
  const expandingOnDrop=!dragging && (moveDetailsCollapsed || moveLayer.hidden===true || moveLayer.classList.contains('dragging'));
  const dropGeometry=expandingOnDrop?sheetGeometry('move'):null;
  if(expandingOnDrop){
    stopSheetResizeMotion();
    dropGeometry.sheet.classList.add('sheet-interacted');
    phone.classList.add('sheet-settling');
    moveDetailsCollapsed=false;
    moveLayer.classList.remove('details-collapsed');
    document.querySelector('#toggle-move-details').setAttribute('aria-expanded','true');
    document.querySelector('#toggle-move-details').setAttribute('aria-label','상세 일정 접기');
  }
  const timeline = calendarView==='week' || calendarView==='day';
  const showSheet=!dragging || pointerMove?.showDetailsDuringDrag!==false;
  const showChrome=showSheet || Boolean(dragging && pointerMove?.keepCollapsedSheet);
  if(showChrome){
    phone.classList.toggle('timeline-move',timeline);
    phone.classList.toggle('timeline-dragging',timeline && dragging);
    updateMoveSheetSize(timeline,dragging);
    phone.classList.add('previewing-move');
  }
  app.querySelectorAll('[data-drop-date]').forEach(day => day.classList.toggle('move-date-unavailable', !canMoveToDate(e, day.dataset.dropDate)));
  app.querySelectorAll('.move-preview-chip').forEach(el => el.remove());
  app.querySelectorAll('.move-target').forEach(el => el.classList.remove('move-target'));
  clearTimelineMoveMarkers();
  syncMovingSource(e);
  const preview = {...e, ...moveSchedule(e,targetDate,times), dismissedConflict:''};
  const conflictingIds = new Set(overlappingEvents(preview).map(item => item.id));
  const conflictMarker = '<span class="timeline-conflict-marker" role="img" aria-label="이동 예정 일정과 시간 겹침" title="같은 시간에 다른 일정이 있어요">!</span>';
  if(timeline){
    app.querySelector(`[data-event="${e.id}"]`)?.classList.add('time-move-source');
    conflictingIds.forEach(id=>{
      const item=app.querySelector(`[data-event="${id}"]`);
      item?.classList.add('time-move-conflict');
      item?.insertAdjacentHTML('beforeend',conflictMarker);
    });
  }
  const cell = app.querySelector(`[data-drop-date="${targetDate}"]`);
  cell?.classList.add('move-target');
  const previewMarkup = `<button type="button" class="event ${eventClass(e)} move-preview-chip movable-event ${targetDate < TODAY ? 'past' : ''}" data-reposition="${e.id}" aria-label="${escapeHTML(e.title)} 이동 예정, 다시 이동하기" title="눌러서 다시 이동하거나 드래그하세요"><span class="event-title">${escapeHTML(e.title)}</span></button>`;
  if (cell?.querySelector('.date-button')) cell.querySelector('.date-button').insertAdjacentHTML('afterend', previewMarkup);
  else cell?.insertAdjacentHTML('beforeend', previewMarkup.replace('class="event ', `class="event timed-event time-preview-chip ${conflictingIds.size?'has-time-overlap':''} `).replace('data-reposition=', `style="top:${minutesOfDay(preview.start)*timelineScale}px;height:${Math.max(28,(minutesOfDay(preview.end)-minutesOfDay(preview.start))*timelineScale-2)}px" data-reposition=`).replace('</button>',`<span class="timed-event-time">${preview.start}–${preview.end}</span>${conflictingIds.size?conflictMarker:''}</button>`));
  document.querySelector('#move-heading').textContent = longDate(targetDate);
  document.querySelector('#move-summary').textContent = restriction ? `${e.title} · 이동할 수 없는 날짜 미리보기` : e.date === targetDate ? `${e.title} · 원래 날짜에 유지 예정` : `${e.title} · ${parseDate(e.date).getMonth() + 1}월 ${parseDate(e.date).getDate()}일에서 이동 예정`;
  const timed = calendarView==='week' || calendarView==='day' || pendingMove?.timed;
  const comparison = document.querySelector('#move-time-comparison');
  comparison.hidden = dragging || !timed;
  if(timed){
    document.querySelector('#move-summary').textContent = `${e.title} · ${dragging ? preview.start+'–'+preview.end : pendingMove?.autoSuggested ? '자동 수정 제안' : '변경 내용을 확인해주세요'}`;
    comparison.innerHTML = `<div class="move-time-pair"><section><h3>변경 전</h3><p>${e.date.replaceAll('-','.')}</p><strong>${e.start}–${e.end}</strong></section><section><h3>변경 후</h3><p>${preview.date.replaceAll('-','.')}</p><strong>${preview.start}–${preview.end}</strong></section></div>`;
  }
  const dayEvents = [...byDate(targetDate).filter(item => item.id !== e.id), {...preview, isPreview:true}].sort((a,b) => a.start.localeCompare(b.start) || Number(a.isPreview || 0) - Number(b.isPreview || 0));
  document.querySelector('#move-day-events').innerHTML = dayEvents.map(item => {
    const conflictsWithMove = item.isPreview ? conflictingIds.size > 0 : conflictingIds.has(item.id);
    return `<article class="move-day-item ${item.isPreview ? 'proposed-move-item' : ''} ${conflictsWithMove ? 'conflicting-move-item' : ''}"><span class="move-item-color ${eventClass(item)}" aria-hidden="true"></span><div><h3><span class="move-item-title">${escapeHTML(item.title)}</span>${item.isPreview ? '<span class="move-item-badge">이동 예정</span>' : ''}</h3><p>${timeLabel(item.start)} – ${timeLabel(item.end)}</p><small>${escapeHTML(item.content)}</small></div></article>`;
  }).join('');
  const overlap = conflictingIds.size > 0;
  const moveWarning = document.querySelector('#move-warning');
  moveWarning.innerHTML = restriction ? `<span class="overlap-message">${restriction}</span> 다른 날짜를 누르거나 다시 드래그해주세요.` : overlap
    ? `<span class="overlap-message">같은 시간에 다른 일정이 있어요.</span> ${dragging ? '손을 놓으면 이동 여부를 선택할 수 있어요.' : timed ? '확인하면 이 날짜와 시간으로 이동합니다.' : '확인하면 이 날짜로 이동합니다.'}`
    : dragging ? (timed?'15분 단위로 시간을 조절해요. 손을 놓으면 변경 내용을 확인해요.':'다른 날짜 위로 움직여보세요. 손을 놓으면 취소·확인을 선택해요.') : timed?'학습 길이와 결과는 유지됩니다. 확인할 때 이동해요.':'시간과 학습 결과는 유지됩니다. 확인할 때 이동해요.';
  const layer = document.querySelector('#move-preview');
  if((pendingMove?.batchDraft || planCreation?.preview) && !restriction)moveWarning.innerHTML=`${overlap?'<span class="overlap-message">같은 시간에 다른 일정이 있어요.</span> ':''}확인은 변경 예정 일정에만 반영돼요. 취소하면 이전 미리보기로 돌아갑니다.`;
  layer.classList.toggle('timeline-preview',timeline);
  document.querySelector('#move-day-events').hidden=timeline;
  layer.hidden = !showChrome;
  if(showSheet && pointerMove?.continuingDetails)layer.classList.add('continuing-details');
  layer.classList.toggle('dragging', dragging);
  layer.querySelector('[role="dialog"]').setAttribute('aria-modal', 'false');
  document.querySelector('#cancel-move').disabled = dragging;
  document.querySelector('#confirm-move').disabled = dragging || Boolean(restriction);
  document.querySelector('#confirm-move').textContent = pendingMove?.autoSuggested ? '적용하기' : '확인';
  liveMove = dragging ? {id:e.id, date:targetDate, start:preview.start, end:preview.end} : null;
  if(showSheet && !timeline)scrollToMoveConflicts();
  // Start after content, buttons and target heights all represent the dropped state.
  if(expandingOnDrop && !settleSheetGeometry('move',true,dropGeometry))phone.classList.remove('sheet-settling');
}
function clearTimelineMoveMarkers() {
  app.querySelectorAll('.timeline-conflict-marker').forEach(el=>el.remove());
  app.querySelectorAll('.time-move-conflict').forEach(el=>el.classList.remove('time-move-conflict'));
  app.querySelectorAll('.time-move-source').forEach(el=>el.classList.remove('time-move-source'));
}
function scrollToMoveConflicts(listSelector='#move-day-events',layerSelector='#move-preview') {
  if (movePreviewScrollFrame !== null) cancelAnimationFrame(movePreviewScrollFrame);
  movePreviewScrollFrame = requestAnimationFrame(() => {
    movePreviewScrollFrame = null;
    if (moveClosing || document.querySelector(layerSelector).hidden) return;
    const list = document.querySelector(listSelector);
    const conflicts = [...list.querySelectorAll('.conflicting-move-item')];
    const proposed = list.querySelector('.proposed-move-item');
    const targets = conflicts.length ? conflicts : proposed ? [proposed] : [];
    const behavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth';
    if (!targets.length) { list.scrollTo({top:0, behavior}); return; }
    const listRect = list.getBoundingClientRect();
    const bounds = item => {
      const rect = item.getBoundingClientRect();
      return {top:rect.top - listRect.top + list.scrollTop, bottom:rect.bottom - listRect.top + list.scrollTop};
    };
    const allBounds = targets.map(bounds);
    let top = Math.min(...allBounds.map(b => b.top)), bottom = Math.max(...allBounds.map(b => b.bottom));
    // Show both sides of the conflict together when they fit; otherwise prioritize
    // an overlapping event. Scroll only the sheet list, never the page.
    if (bottom - top > list.clientHeight - 24) {
      const overlappingEvent = list.querySelector('.conflicting-move-item:not(.proposed-move-item)') || targets[0];
      ({top, bottom} = bounds(overlappingEvent));
    }
    const target = Math.max(0, top - Math.max(12, (list.clientHeight - (bottom - top)) / 2));
    list.scrollTo({top:target, behavior});
  });
}
function clearMovePreviewArtifacts() {
  stopSheetResizeMotion();
  document.querySelector('.move-sheet').classList.remove('sheet-interacted');
  clearTimelineMoveMarkers();
  app.querySelectorAll('.plan-preview-drag-source').forEach(el=>el.classList.remove('plan-preview-drag-source'));
  app.querySelectorAll('.move-preview-chip').forEach(el=>el.remove());
  app.querySelectorAll('.move-target').forEach(el=>el.classList.remove('move-target'));
  app.querySelectorAll('.move-date-unavailable').forEach(el=>el.classList.remove('move-date-unavailable'));
  document.querySelector('#move-preview').classList.remove('dragging');
  document.querySelector('#move-preview').classList.remove('continuing-details');
  document.querySelector('#move-preview').classList.remove('details-collapsed');
  moveDetailsCollapsed=false;
  document.querySelector('#toggle-move-details').setAttribute('aria-expanded','true');
  document.querySelector('#toggle-move-details').setAttribute('aria-label','상세 일정 접기');
  liveMove=null;
}
function closeMovePreview() {
  if (moveClosing) return moveClosePromise;
  stopSheetResizeMotion();
  const layer = document.querySelector('#move-preview');
  if (layer.hidden) {clearMovePreviewArtifacts();return Promise.resolve();}
  moveClosing = true;
  if (movePreviewScrollFrame !== null) {
    cancelAnimationFrame(movePreviewScrollFrame); movePreviewScrollFrame = null;
  }
  layer.classList.remove('dragging');
  layer.classList.add('closing');
  app.inert = true;
  document.querySelector('#cancel-move').disabled = true;
  document.querySelector('#confirm-move').disabled = true;
  const sheet = layer.querySelector('.move-sheet');
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  // Begin at the current visual position, even if the opening animation is unfinished.
  const current = getComputedStyle(sheet);
  const phone = document.querySelector('.phone');
  const returningToPlan=Boolean(planCalendarPreview && phone.classList.contains('plan-moving'));
  const surface=returningToPlan?app.querySelector(calendarView==='month'?'.grid':'.time-view'):null;
  const surfaceHeight=surface?.getBoundingClientRect?.().height ?? surface?.clientHeight;
  const targetGridHeight=Math.max(180,phone.clientHeight-319);
  if(returningToPlan){
    phone.style.setProperty('--plan-preview-grid-height',`${targetGridHeight}px`);
    phone.classList.add('plan-returning');
  }
  phone.classList.add('move-sheet-closing');
  phone.classList.remove('previewing-move');
  const animation = !reducedMotion && sheet.animate ? sheet.animate([
    {transform:current.transform, opacity:current.opacity},
    {transform:'translateY(100%)', opacity:0.35}
  ], {duration:340, easing:'cubic-bezier(.4,0,.7,.3)', fill:'forwards'}) : null;
  const companions=[];
  if(returningToPlan && !reducedMotion){
    const options={duration:340,easing:'cubic-bezier(.4,0,.7,.3)',fill:'forwards'};
    if(surface?.animate && Number.isFinite(surfaceHeight))companions.push(surface.animate([
      {height:`${surfaceHeight}px`},{height:`${targetGridHeight+(calendarView==='month'?0:35)}px`}
    ],options));
    const controls=document.querySelector('#plan-adjustment-preview');
    if(controls.animate)companions.push(controls.animate([
      {opacity:0,transform:'translateY(16px)'},{opacity:1,transform:'translateY(0)'}
    ],options));
  }
  const animations=[animation,...companions].filter(Boolean);
  moveClosePromise = Promise.all(animations.map(item=>item.finished.catch(() => {}))).then(() => {
    layer.hidden = true;
    animation?.cancel();
    companions.forEach(item=>item.cancel());
    layer.classList.remove('closing');
    phone.classList.remove('move-sheet-closing');
    phone.classList.remove('timeline-move');
    phone.classList.remove('timeline-dragging');
    layer.classList.remove('timeline-preview');
    clearMovePreviewArtifacts();
    app.inert = false;
    moveClosing = false;
    moveClosePromise = null;
  });
  return moveClosePromise;
}
async function cancelMove() {
  if(!pendingMove && normalConflictDay)return finishNormalConflictSession(false);
  if(!pendingMove && planTimeEdit)return finishPlanTimeEdit(false);
  if (!pendingMove || moveClosing) return;
  const move = pendingMove;
  movePicking = false; keyboardMove = null;
  await closeMovePreview();
  pendingMove = null; selectedDate = move.selectedDate; viewDate = move.viewDate;
  if(planCreation?.preview){finishCreationDraftMove(move,false);return;}
  if(move.batchDraft){finishPlanDraftMove(move,false);return;}
  if(move.returnToMonth){normalConflictDay=null;calendarView='month';selectedEvent=null;focusAfterRender=move.id;renderCalendar();}
  else if(move.returnToDetail){focusAfterRender=null;openEvent(move.id);}
  else {focusAfterRender = move.id; renderCalendar();}
}
async function confirmMove() {
  if(!pendingMove && normalConflictDay)return finishNormalConflictSession(true);
  if(!pendingMove && planTimeEdit)return finishPlanTimeEdit(true);
  if (!pendingMove || moveClosing) return;
  if (pendingMove.awaitingTarget) { toast(pendingMove.timed?'일정을 드래그해서 시간을 선택해주세요.':'이동할 날짜를 선택하거나 일정을 드래그해주세요.'); return; }
  const move = pendingMove, e = movePreviewEvent(move.id);
  const restriction = moveRestriction(e, move.date);
  if (restriction) { toast(restriction); return; }
  movePicking = false; keyboardMove = null;
  if (!canMoveEvent(e) || !['date','start','end'].every(key => e[key] === move.before[key])) {
    await cancelMove(); toast('일정이 변경되어 이동을 취소했어요. 다시 시도해주세요.'); return;
  }
  await closeMovePreview();
  if(planCreation?.preview){pendingMove=null;finishCreationDraftMove(move,true);return;}
  if(move.batchDraft){pendingMove=null;finishPlanDraftMove(move,true);return;}
  const dateChanged = e.date !== move.date;
  const timeChanged = e.start !== move.start || e.end !== move.end;
  const fromMonth=calendarView==='month';
  Object.assign(e,{date:move.date,start:move.start,end:move.end});
  if (dateChanged || timeChanged) e.dismissedConflict = '';
  pendingMove = null;
  selectedDate = e.date; viewDate = parseDate(e.date); focusAfterRender = e.id;
  if(move.returnToMonth){normalConflictDay=null;calendarView='month';selectedEvent=null;renderCalendar();}
  else if(move.returnToDetail){focusAfterRender=null;openEvent(e.id);}
  else renderCalendar();
  toast(dateChanged || timeChanged ? `${parseDate(e.date).getMonth() + 1}월 ${parseDate(e.date).getDate()}일${move.timed?' '+e.start+'–'+e.end:''}로 이동했어요` : '원래 일정으로 유지했어요');
  if(fromMonth&&dateChanged&&hasConflict(e))offerTimeAdjustment(e,move.returnToDetail);
}
let pendingTimeAdjustment=null,normalConflictDay=null;
function offerTimeAdjustment(e,returnToDetail=false) {
  const conflicts=overlappingEvents(e);
  if(!conflicts.length)return;
  const batchDraft=Boolean(planCalendarPreview);
  pendingTimeAdjustment={id:e.id,returnToDetail,batchDraft};
  document.querySelector('#time-conflict-description').textContent=batchDraft
    ? '변경 예정 날짜에 다른 일정과 시간이 겹쳐요. 확인을 누르면 일 보기에서 변경 예정 시간을 드래그로 수정할 수 있어요.'
    : '날짜 이동은 완료됐지만 다른 일정과 시간이 겹쳐요. 확인을 누르면 일 보기에서 시간을 드래그로 수정할 수 있어요.';
  document.querySelector('#time-conflict-cancel-note').textContent=batchDraft
    ? '취소하면 변경 예정 날짜와 현재 시간을 유지해요. 실제 일정은 전체 미리보기의 마지막 확인 때 반영돼요.'
    : '취소하면 이동한 날짜와 현재 시간을 그대로 유지합니다.';
  document.querySelector('#time-conflict-details').innerHTML=`<div class="time-conflict-source"><strong>${escapeHTML(e.title)}</strong><p>${longDate(e.date)}<br>${e.start}–${e.end}</p></div><p class="time-conflict-label">겹치는 일정</p>${conflicts.map(other=>`<div class="time-conflict-row"><span>${escapeHTML(other.title)}</span><small>${other.start}–${other.end}</small></div>`).join('')}`;
  showPhoneDialog('#time-conflict-prompt');
}
function startDayTimeAdjustment(id) {
  const e=events.find(e=>e.id===id);
  if(!isMovableStudy(e))return;
  normalConflictDay={date:e.date,items:[],returnView:{calendarView:'month',selectedDate:e.date,viewDate:parseDate(e.date)}};
  calendarView='day';selectedEvent=null;searchOpen=false;
  selectedDate=e.date;viewDate=parseDate(e.date);
  renderCalendar();
  pendingMove=null;movePicking=false;keyboardMove=null;
  document.querySelector('.phone').classList.add('conflict-session-active');
  document.querySelector('.phone').style.setProperty('--conflict-session-height',`${Math.max(180,document.querySelector('.phone').clientHeight-284)}px`);
  document.querySelector('#conflict-session-controls').hidden=false;
  updateNormalConflictSummary();
  const scroller=app.querySelector('.time-scroll');if(scroller)scroller.scrollTop=Math.max(0,minutesOfDay(e.start)-30);
  app.querySelector('[data-reposition]')?.focus({preventScroll:true});
}
function updateNormalConflictSummary() {
  if(!normalConflictDay)return;
  const count=normalConflictDay.items.filter(item=>['date','start','end'].some(key=>item.before[key]!==item.after[key])).length;
  const conflicts=byDate(normalConflictDay.date).filter(e=>hasConflict(e)).length;
  document.querySelector('#conflict-session-summary').textContent=`${count}개 일정 수정 예정${conflicts?` · 충돌 ${conflicts}개`:''}. 여러 일정을 연속으로 옮긴 뒤 마지막에 확인하거나 취소해주세요.`;
}
function stageConflictDayDrop(id,schedule) {
  const scrollTop=app.querySelector('.time-scroll')?.scrollTop || 0;
  if(schedule){
    if(normalConflictDay && schedule.date===normalConflictDay.date){
      let item=normalConflictDay.items.find(item=>item.id===id);
      if(!item){const e=events.find(e=>e.id===id);item={id,before:{date:e.date,start:e.start,end:e.end},after:{...schedule}};normalConflictDay.items.push(item);}
      item.after={...schedule};
    }else if(planTimeEdit){
      const item=planCalendarPreview.items.find(item=>item.id===id);
      item.after={...schedule};item.manuallyAdjusted=true;item.manualTimeEdit=true;
      planTimeEdit.draft={...schedule};
    }
  }
  const restore=()=>{
    pendingMove=null;document.querySelector('.phone').classList.remove('plan-moving');
    renderCalendar();
    const scroller=app.querySelector('.time-scroll');if(scroller)scroller.scrollTop=scrollTop;
    if(normalConflictDay)updateNormalConflictSummary();else if(planTimeEdit)updatePlanTimeSummary();
  };
  if(document.querySelector('#move-preview').hidden){clearMovePreviewArtifacts();restore();}
  else closeMovePreview().then(restore);
}
function finishNormalConflictSession(apply) {
  const session=normalConflictDay;if(!session || pointerMove || moveClosing)return;
  if(apply){
    if(session.items.some(item=>{const e=events.find(e=>e.id===item.id);return !e || !['date','start','end'].every(key=>e[key]===item.before[key]);})){
      toast('일정이 변경됐어요. 수정 내용을 다시 확인해주세요.');return;
    }
    session.items.forEach(item=>{const e=events.find(e=>e.id===item.id);Object.assign(e,item.after);e.dismissedConflict='';});
  }
  normalConflictDay=null;pendingMove=null;selectedEvent=null;
  calendarView=session.returnView.calendarView;selectedDate=session.returnView.selectedDate;viewDate=session.returnView.viewDate;
  document.querySelector('#conflict-session-controls').hidden=true;
  document.querySelector('.phone').classList.remove('conflict-session-active');
  renderCalendar();toast(apply?'조정한 시간을 모두 반영했어요':'이번 시간 수정을 모두 취소했어요');
}
document.querySelector('#confirm-conflict-session').addEventListener('click',()=>finishNormalConflictSession(true));
document.querySelector('#cancel-conflict-session').addEventListener('click',()=>finishNormalConflictSession(false));
document.querySelector('#conflict-session-controls').addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();finishNormalConflictSession(false);}});
document.querySelector('#time-conflict-prompt').addEventListener('close',()=>{pendingTimeAdjustment=null;});
document.querySelector('#edit-conflicting-time').addEventListener('click',async ()=>{
  const suggestion=pendingTimeAdjustment;
  pendingTimeAdjustment=null;
  await closePhoneDialog('#time-conflict-prompt');
  if(suggestion?.batchDraft){if(planCalendarPreview)startPlanTimeEdit(suggestion.id);}
  else if(suggestion)startDayTimeAdjustment(suggestion.id);
});
document.querySelector('#cancel-move').addEventListener('click', cancelMove);
document.querySelector('#cancel-move-backdrop').addEventListener('click', cancelMove);
document.querySelector('#confirm-move').addEventListener('click', confirmMove);
function cycleMoveFocus(action) {
  if (!pendingMove || action.key !== 'Tab') return false;
  action.preventDefault();
  const controls = [app.querySelector('[data-reposition]'), document.querySelector('#cancel-move'), document.querySelector('#confirm-move')].filter(el => el && !el.disabled);
  const index = controls.indexOf(document.activeElement);
  controls[(index + (action.shiftKey ? -1 : 1) + controls.length) % controls.length]?.focus();
  return true;
}
document.querySelector('#move-preview').addEventListener('keydown', action => {
  if (action.key === 'Escape') { action.preventDefault(); cancelMove(); }
  cycleMoveFocus(action);
});
function markDropTarget(date) {
  app.querySelectorAll('.drag-target').forEach(el => el.classList.remove('drag-target'));
  app.querySelectorAll('.drop-blocked').forEach(el => el.classList.remove('drop-blocked'));
  if (date) app.querySelector(`[data-drop-date="${date}"]`)?.classList.add('drag-target');
}
function clearPointerMove(keepPreview = false) {
  if (!pointerMove) return;
  const wasPlanDraft=Boolean(pointerMove.planAdjustment);
  const wasPlanTime=Boolean(pointerMove.planAdjustment && planTimeEdit);
  if(pointerMove.planAdjustment){
    if(!liveMove)pointerMove.source.classList.remove('plan-preview-drag-source');
    clearPlanDraftTarget(Boolean(liveMove));
    if(keepPreview!==true)hidePlanDragDetails();
    updatePlanCalendarSummary();
  }
  dragPageSlide?.finish();
  clearPageEdgeDwell();
  document.querySelector('#drag-page-zones').hidden=true;
  clearTimeout(pointerMove.timer);
  if(pointerMove.scrollFrame)cancelAnimationFrame(pointerMove.scrollFrame);
  pointerMove.source.classList.remove('drag-source');
  const captureTarget = pointerMove.captureTarget;
  if (captureTarget?.hasPointerCapture?.(pointerMove.pointerId)) captureTarget.releasePointerCapture(pointerMove.pointerId);
  pointerMove.ghost?.remove();
  pointerMove = null; markDropTarget(null);
  if(wasPlanTime)updatePlanTimeSummary();
  document.body.classList.remove('moving-event');
  if (keepPreview !== true && liveMove) {
    if (pendingMove) cancelMove(); else if(wasPlanDraft)closePlanDragPreview();else closeMovePreview();
  }
}
function startPointerMove() {
  if (!pointerMove || pointerMove.active) return;
  pointerMove.active = true;
  app.setPointerCapture(pointerMove.pointerId);
  pointerMove.captureTarget = app;
  pointerMove.source.classList.add('drag-source');
  const ghost = pointerMove.source.cloneNode(true);
  ghost.removeAttribute('id'); ghost.removeAttribute('data-event'); ghost.removeAttribute('data-reposition'); ghost.setAttribute('aria-hidden','true');
  ghost.classList.remove('move-preview-chip');
  ghost.classList.add('drag-floating'); ghost.style.width = `${Math.max(90, pointerMove.source.offsetWidth)}px`;
  if(pointerMove.timed)ghost.style.height='44px';
  document.body.append(ghost); pointerMove.ghost = ghost;
  document.body.classList.add('moving-event');
  if(pointerMove.planAdjustment){
    pointerMove.source.classList.add('plan-preview-drag-source');
    document.querySelector('#apply-plan-adjustment').disabled=true;
    positionPointerGhost(pointerMove.x,pointerMove.y);
    if(pointerMove.timed)startTimelineAutoScroll();
    return;
  }
  const e = movePreviewEvent(pointerMove.id);
  paintMovePreview(e, pendingMove?.date || e.date, true);
  positionPointerGhost(pointerMove.x, pointerMove.y);
  if(pointerMove.timed)startTimelineAutoScroll();
}
const EDGE_DWELL_MS=800;
function clearPageEdgeDwell() {
  if(pointerMove){clearTimeout(pointerMove.edgeTimer);pointerMove.edgeTimer=null;pointerMove.edgeDirection=0;}
  for(const id of ['#drag-edge-prev','#drag-edge-next'])document.querySelector(id).classList.remove('armed');
}
function pageEdgeAt(x,y) {
  if(!['day','week','month'].includes(calendarView))return 0;
  const phone=document.querySelector('.phone');
  const surface=app.querySelector(calendarView==='month'?'.grid':'.time-scroll');
  if(!phone.getBoundingClientRect||!surface?.getBoundingClientRect)return 0;
  const frame=phone.getBoundingClientRect(), area=surface.getBoundingClientRect();
  if(y<area.top||y>area.bottom||x<frame.left-8||x>frame.right+8)return 0;
  const width=Math.max(28,Math.min(36,frame.width*.085));
  return x<=frame.left+width?-1:x>=frame.right-width?1:0;
}
function updatePageEdgeDwell(x,y) {
  if(!pointerMove?.active)return;
  if(planTimeEdit || normalConflictDay){clearPageEdgeDwell();return;}
  const layer=document.querySelector('#drag-page-zones');
  layer.hidden=false;
  const unit={day:'일',week:'주',month:'달'}[calendarView];
  document.querySelector('#drag-edge-prev').querySelector('small').textContent=`이전 ${unit}`;
  document.querySelector('#drag-edge-next').querySelector('small').textContent=`다음 ${unit}`;
  const surface=app.querySelector(calendarView==='month'?'.grid':'.time-scroll');
  const phone=document.querySelector('.phone');
  if(surface?.getBoundingClientRect&&phone.getBoundingClientRect){
    const area=surface.getBoundingClientRect(),frame=phone.getBoundingClientRect();
    layer.style.top=`${area.top-frame.top}px`;layer.style.height=`${area.height}px`;
  }
  const direction=pageEdgeAt(x,y);
  if(direction!==pointerMove.edgeDirection)clearPageEdgeDwell();
  if(!direction||pointerMove.edgeTimer)return;
  pointerMove.edgeDirection=direction;
  const zone=document.querySelector(direction<0?'#drag-edge-prev':'#drag-edge-next');
  zone.classList.remove('armed');void zone.offsetWidth;zone.classList.add('armed');
  const drag=pointerMove;
  drag.edgeTimer=setTimeout(function pageEdgeDwell(){
    if(pointerMove!==drag||!drag.active)return;
    drag.edgeTimer=null;
    const current=pageEdgeAt(drag.lastX??drag.x,drag.lastY??drag.y);
    if(current!==direction){clearPageEdgeDwell();return;}
    navigateDraggingPage(direction);
  },EDGE_DWELL_MS);
}
function navigateDraggingPage(direction) {
  if(!pointerMove?.active)return;
  dragPageSlide?.finish();
  const outgoing=captureDragPage();
  const e=movePreviewEvent(pointerMove.id);
  const previous=pointerMove.planAdjustment?{date:pointerMove.targetDate || e.date,start:e.start,end:e.end}:liveMove||{date:e.date,start:e.start,end:e.end};
  const oldScroll=app.querySelector('.time-scroll')?.scrollTop||0;
  viewDate=shiftedPage(viewDate,direction);
  focusAfterRender=null;
  renderCalendar();
  const scroller=app.querySelector('.time-scroll');if(scroller)scroller.scrollTop=oldScroll;
  let target=parseDate(previous.date);
  if(calendarView==='month'){
    const day=target.getDate();target.setDate(1);target.setMonth(target.getMonth()+direction);
    target.setDate(Math.min(day,new Date(target.getFullYear(),target.getMonth()+1,0).getDate()));
  }else target.setDate(target.getDate()+direction*(calendarView==='week'?7:1));
  if(pointerMove.planAdjustment)paintPlanDraftTarget(e,dateKey(target));
  else paintMovePreview(e,dateKey(target),true,previous);
  positionPointerGhost(pointerMove.lastX??pointerMove.x,pointerMove.lastY??pointerMove.y);
  if(outgoing)animateDragPage(outgoing,direction);
}
function cloneDragPage(surface) {
  const clone=surface.cloneNode(true);
  const scroll=surface.querySelector('.time-scroll');
  if(scroll)clone.dataset.slideScrollTop=String(scroll.scrollTop);
  clone.style.cssText='position:absolute;inset:0;width:100%;height:100%;min-height:0;margin:0;opacity:1;transition:none;pointer-events:none';
  return clone;
}
function captureDragPage(selector=null) {
  const surface=app.querySelector(selector || (calendarView==='month'?'.grid':'.time-view'));
  if(!surface?.animate||window.matchMedia('(prefers-reduced-motion: reduce)').matches)return null;
  return cloneDragPage(surface);
}
function animateDragPage(outgoing,direction,offset=0,selector=null) {
  const phone=document.querySelector('.phone');
  const surface=app.querySelector(selector || (calendarView==='month'?'.grid':'.time-view'));
  const rect=surface.getBoundingClientRect(), frame=phone.getBoundingClientRect();
  const overlay=document.createElement('div');
  overlay.className='drag-page-slide';overlay.setAttribute('aria-hidden','true');overlay.inert=true;
  overlay.style.cssText=`left:${rect.left-frame.left}px;top:${rect.top-frame.top}px;width:${rect.width}px;height:${rect.height}px`;
  const incoming=cloneDragPage(surface);
  overlay.append(outgoing,incoming);phone.append(overlay);
  for(const page of [outgoing,incoming]){
    const scroll=page.querySelector('.time-scroll');
    if(scroll)scroll.scrollTop=Number(page.dataset.slideScrollTop||0);
  }
  const originalOpacity=surface.style.opacity;
  // Animate visual copies so hit testing and pointer capture stay on the real calendar.
  surface.style.opacity='0';
  const options={duration:320,easing:'cubic-bezier(.22,.75,.18,1)',fill:'both'};
  const animations=[
    outgoing.animate([{transform:offset?`translateX(${offset}px)`:'translateX(0)'},{transform:`translateX(${-direction*100}%)`}],options),
    incoming.animate([{transform:offset?`translateX(calc(${direction*100}% + ${offset}px))`:`translateX(${direction*100}%)`},{transform:'translateX(0)'}],options)
  ];
  let finished=false;
  const slide={finish(){
    if(finished)return;finished=true;
    surface.style.opacity=originalOpacity;
    overlay.remove();animations.forEach(animation=>animation.cancel());
    if(dragPageSlide===slide)dragPageSlide=null;
  }};
  dragPageSlide=slide;
  Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(()=>slide.finish());
}
function timelineScheduleAt(e, column, clientY, grabOffset = 0) {
  const rect=column.getBoundingClientRect();
  const length=minutesOfDay(e.end)-minutesOfDay(e.start);
  if(!rect.height||length<=0)return null;
  const minute=(clientY-rect.top)*1440/rect.height-grabOffset;
  const latest=Math.max(0,Math.floor((1439-length)/15)*15);
  const start=Math.max(0,Math.min(latest,Math.round(minute/15)*15));
  return {date:column.dataset.dropDate,start:minutesToTime(start),end:minutesToTime(start+length)};
}
function startTimelineAutoScroll() {
  function tick(){
    if(!pointerMove?.active||!pointerMove.timed)return;
    const scroller=app.querySelector('.time-scroll');
    if(scroller){
      const rect=scroller.getBoundingClientRect(), y=pointerMove.lastY??pointerMove.y;
      const x=pointerMove.lastX??pointerMove.x;
      if(x>=rect.left&&x<=rect.right&&y>=rect.top-12&&y<=rect.bottom+12){
        const amount=y<rect.top+28?-7:y>rect.bottom-28?7:0;
        if(amount){const before=scroller.scrollTop;scroller.scrollTop+=amount;if(scroller.scrollTop!==before)positionPointerGhost(x,y);}
      }
    }
    if(pointerMove)pointerMove.scrollFrame=requestAnimationFrame(tick);
  }
  pointerMove.scrollFrame=requestAnimationFrame(tick);
}
function positionPointerGhost(x,y) {
  if (!pointerMove?.ghost) return;
  pointerMove.lastX=x;pointerMove.lastY=y;
  pointerMove.ghost.style.left = `${x + 10}px`; pointerMove.ghost.style.top = `${y - 28}px`;
  updatePageEdgeDwell(x,y);
  const day = document.elementFromPoint(x,y)?.closest('[data-drop-date]');
  if(pointerMove.planAdjustment){
    const e=calendarEvents().find(e=>e.id===pointerMove.id),date=day?.dataset.dropDate || null;
    if(planTimeEdit){
      const schedule=day && date===planTimeEdit.before.date?timelineScheduleAt(e,day,y,pointerMove.grabOffset):null;
      pointerMove.targetSchedule=schedule;pointerMove.targetDate=schedule?.date || null;
      paintPlanTimeTarget(e,schedule);return;
    }
    pointerMove.targetDate=date && canMoveToDate(e,date)?date:null;
    pointerMove.blockedDate=date && !canMoveToDate(e,date)?date:null;
    paintPlanDraftTarget(e,date);return;
  }
  const e = movePreviewEvent(pointerMove.id);
  const targetSchedule=pointerMove.timed&&day?timelineScheduleAt(e,day,y,pointerMove.grabOffset):null;
  syncMovingSource(e);
  pointerMove.targetSchedule=targetSchedule;
  if(targetSchedule){const label=pointerMove.ghost.querySelector('.timed-event-time');if(label)label.textContent=`${targetSchedule.start}–${targetSchedule.end}`;}
  if (day && !canMoveToDate(e, day.dataset.dropDate)) {
    pointerMove.targetDate = null;
    pointerMove.blockedDate = day.dataset.dropDate;
    if (liveMove?.date !== day.dataset.dropDate || targetSchedule?.start!==liveMove?.start) paintMovePreview(e, day.dataset.dropDate, true, targetSchedule);
    markDropTarget(null); day.classList.add('drop-blocked');
    return;
  }
  const wasBlocked = Boolean(pointerMove.blockedDate);
  pointerMove.blockedDate = null;
  pointerMove.targetDate = day?.dataset.dropDate || null;
  markDropTarget(pointerMove.targetDate);
  if (pointerMove.targetDate && (wasBlocked || liveMove?.date !== pointerMove.targetDate || (targetSchedule&&liveMove?.start!==targetSchedule.start))) {
    paintMovePreview(e, pointerMove.targetDate, true, targetSchedule);
  }
}
app.addEventListener('pointerdown', action => {
  if(timelineZoomGesture)return;
  if(beginCalendarSwipe(action))return;
  if(action.target.closest('[data-agenda-drag]')?.dataset.agendaDrag){beginMonthAgendaGesture(action);return;}
  if(planCreation && !planCreation.preview){beginCreationBrush(action);return;}
  const source = action.target.closest('.movable-event');
  if(planCreation?.preview){const e=movePreviewEvent(source?.dataset.reposition || source?.dataset.event);if(!e?.isCreationPreview)return;}
  if (!source || action.button !== 0 || (pendingMove && !source.dataset.reposition && !normalConflictDay) || pointerMove || moveClosing || selectedEvent || searchOpen) return;
  const moveChromeOpen=document.querySelector('#move-preview').hidden===false;
  const moveDetailsOpen=moveChromeOpen && !moveDetailsCollapsed;
  const planDetailsOpen=Boolean(planCalendarPreview && document.querySelector('#plan-drag-details').hidden===false);
  const detailState={showDetailsDuringDrag:moveDetailsOpen || planDetailsOpen,continuingDetails:!moveDetailsOpen && planDetailsOpen,keepCollapsedSheet:moveChromeOpen && moveDetailsCollapsed};
  if(planCalendarPreview){
    const e=calendarEvents().find(e=>e.id===(source.dataset.reposition || source.dataset.event));
    if(planTimeEdit){if(!canEditPlanDayEvent(e))return;selectPlanTimeTarget(e);}
    else if(!e?.isPlanAdjustmentPreview)return;
    const timed=Boolean(planTimeEdit),top=timed&&source.getBoundingClientRect?source.getBoundingClientRect().top:action.clientY;
    pointerMove={id:e.id,source,pointerId:action.pointerId,pointerType:action.pointerType,x:action.clientX,y:action.clientY,active:false,timed,grabOffset:Math.max(0,Math.min(minutesOfDay(e.end)-minutesOfDay(e.start),(action.clientY-top)/(timed?timelineScale:1))),planAdjustment:true,originViewDate:pendingMove?.viewDate || new Date(viewDate),originSelectedDate:pendingMove?.selectedDate || selectedDate,...detailState};
    if(action.pointerType==='touch')pointerMove.timer=setTimeout(startPointerMove,250);
    return;
  }
  const e = movePreviewEvent(source.dataset.reposition || source.dataset.event);
  if (!canMoveEvent(e)) return;
  keyboardMove = null;
  const timed=calendarView==='week'||calendarView==='day';
  const sourceTop=timed&&source.getBoundingClientRect?source.getBoundingClientRect().top:action.clientY;
  const grabOffset=Math.max(0,Math.min(minutesOfDay(e.end)-minutesOfDay(e.start),(action.clientY-sourceTop)/(timed?timelineScale:1)));
  pointerMove = {id:e.id, source, pointerId:action.pointerId, pointerType:action.pointerType, x:action.clientX, y:action.clientY, active:false,timed,grabOffset,originViewDate:new Date(viewDate),originSelectedDate:selectedDate,hadPending:Boolean(pendingMove),...detailState};
  // Touch users can hold briefly before dragging; a regular tap still opens details.
  if (action.pointerType === 'touch') pointerMove.timer = setTimeout(startPointerMove, 250);
});
document.addEventListener('pointermove', action => {
  if(timelineZoomGesture)return;
  if(calendarSwipe){moveCalendarSwipe(action);return;}
  if(monthAgendaGesture){moveMonthAgendaGesture(action);return;}
  if(creationBrush){moveCreationBrush(action);return;}
  if(sheetGesture){moveSheetGesture(action);return;}
  if (!pointerMove || pointerMove.pointerId !== action.pointerId) return;
  const distance = Math.hypot(action.clientX-pointerMove.x, action.clientY-pointerMove.y);
  if (!pointerMove.active && distance > 7) startPointerMove();
  if (pointerMove.active) { action.preventDefault(); positionPointerGhost(action.clientX, action.clientY); }
}, {passive:false});
document.addEventListener('pointerup', action => {
  if(timelineZoomGesture)return;
  if(calendarSwipe){endCalendarSwipe(action);return;}
  if(monthAgendaGesture){endMonthAgendaGesture(action);return;}
  if(creationBrush){endCreationBrush(action);return;}
  if(sheetGesture){endSheetGesture(action);return;}
  if (!pointerMove || pointerMove.pointerId !== action.pointerId) return;
  if (pointerMove.active) positionPointerGhost(action.clientX, action.clientY);
  const {id, planAdjustment, active, targetDate, blockedDate, targetSchedule,originViewDate,originSelectedDate,hadPending} = pointerMove;
  clearPointerMove(true);
  if (active) {
    suppressEventClickUntil = Date.now() + 400;
    if(normalConflictDay || planTimeEdit){stageConflictDayDrop(id,targetSchedule);return;}
    if(planAdjustment){
      if(targetDate || blockedDate){
        openPlanDraftMove(id,targetDate || blockedDate,targetSchedule,originViewDate,originSelectedDate);
      }else{
        hidePlanDragDetails();
        if(pendingMove)cancelMove();else closePlanDragPreview();
      }
      return;
    }
    if (blockedDate) {
      showBlockedMovePreview(movePreviewEvent(id), blockedDate,targetSchedule);
      if(pendingMove&&!hadPending){pendingMove.viewDate=originViewDate;pendingMove.selectedDate=originSelectedDate;}
      return;
    }
    if (!targetDate || !openMovePreview(id, targetDate,targetSchedule)) {
      if (pendingMove) cancelMove(); else closeMovePreview();
    }
    if(pendingMove&&!hadPending){pendingMove.viewDate=originViewDate;pendingMove.selectedDate=originSelectedDate;}
  }
});
document.addEventListener('pointercancel', event=>{endCalendarSwipe(event,true);endMonthAgendaGesture(event,true);endCreationBrush(event,true);cancelSheetGesture();clearPointerMove(event);});
window.addEventListener('blur', ()=>{endTimelineZoom(null,true);endCalendarSwipe(null,true);endMonthAgendaGesture(null,true);endCreationBrush(null,true);cancelSheetGesture();clearPointerMove();});
function handleMoveKey(action) {
  if(normalConflictDay && action.key==='Escape'){
    action.preventDefault();if(pointerMove)clearPointerMove();else finishNormalConflictSession(false);return true;
  }
  if(planCalendarPreview && action.key==='Escape'){
    action.preventDefault();
    if(pointerMove?.planAdjustment)clearPointerMove();else if(pendingMove)cancelMove();else cancelPlanAdjustment();
    return true;
  }
  if (cycleMoveFocus(action)) return true;
  if (pointerMove?.active && action.key === 'Escape') {
    action.preventDefault(); clearPointerMove(); suppressEventClickUntil = Date.now() + 400; return true;
  }
  if (pendingMove && action.key === 'Escape') { action.preventDefault(); cancelMove(); return true; }
  if (pendingMove && !movePicking) return false;
  const source = action.target.closest('.movable-event');
  if (!keyboardMove && source && action.key.toLowerCase() === 'm') {
    action.preventDefault();
    const e = events.find(e => e.id === (source.dataset.reposition || source.dataset.event));
    if (!isMovableStudy(e)) return true;
    keyboardMove = {id:e.id, date:e.date};
    markDropTarget(e.date); toast('방향키로 날짜 선택 · Enter 미리보기 · Esc 취소'); return true;
  }
  if (!keyboardMove) return false;
  if (action.key === 'Escape') { action.preventDefault(); keyboardMove = null; markDropTarget(null); return true; }
  if (action.key === 'Enter') {
    action.preventDefault(); const move = keyboardMove; keyboardMove = null; markDropTarget(null); if (!openMovePreview(move.id, move.date) && pendingMove) cancelMove(); return true;
  }
  const offsets = {ArrowLeft:-1, ArrowRight:1, ArrowUp:-7, ArrowDown:7};
  if (Object.hasOwn(offsets,action.key)) {
    action.preventDefault(); const date = parseDate(keyboardMove.date); date.setDate(date.getDate()+offsets[action.key]);
    if (!canMoveToDate(events.find(e=>e.id===keyboardMove.id), dateKey(date))) { showBlockedMovePreview(events.find(e=>e.id===keyboardMove.id), dateKey(date)); return true; }
    if (app.querySelector(`[data-drop-date="${dateKey(date)}"]`)) { keyboardMove.date = dateKey(date); markDropTarget(keyboardMove.date); if (movePicking) paintMovePreview(events.find(e=>e.id===keyboardMove.id), keyboardMove.date, true); }
    return true;
  }
  return false;
}
function renderSearch(query) {
  const matches = events.filter(e => isEventVisible(e) && (e.title.includes(query) || e.content.includes(query))).sort((a, b) => a.date.localeCompare(b.date));
  document.querySelector('#search-results').innerHTML = matches.length ? matches.map(e => `<button class="search-result" data-event="${e.id}">${escapeHTML(e.title)}<small>${longDate(e.date)} · ${timeLabel(e.start)}</small></button>`).join('') : '<p class="empty">검색 결과가 없습니다</p>';
}
let studyDeletionId=null,deletedStudyUndo=null,deletedStudyUndoTimer=null;
function clearDeletedStudyUndo(){
  clearTimeout(deletedStudyUndoTimer);deletedStudyUndoTimer=null;deletedStudyUndo=null;
  const notice=document.querySelector('#study-delete-undo');notice.classList.remove('visible');notice.inert=true;notice.setAttribute('aria-hidden','true');
}
function offerDeletedStudyUndo(snapshot){
  clearDeletedStudyUndo();clearDeletedPlanUndo();deletedStudyUndo=snapshot;
  const notice=document.querySelector('#study-delete-undo');notice.inert=false;notice.setAttribute('aria-hidden','false');notice.classList.add('visible');
  deletedStudyUndoTimer=setTimeout(()=>clearDeletedStudyUndo(),8000);
}
document.querySelector('#undo-study-delete').addEventListener('click',async()=>{
  const saved=deletedStudyUndo;if(!saved)return;
  clearDeletedStudyUndo();
  if(!plans.some(plan=>plan.id===(saved.event.planId || 'engineer')))return;
  if(!events.some(e=>e.id===saved.event.id))events.splice(Math.min(saved.index,events.length),0,saved.event);
  renderPlanList();await closePlansDrawer();
  animateDetailNavigation(()=>{
    if(planCreation)cancelPlanCreation();
    settingsReturn=null;
    eventDetailReturn=saved.ui.returnView;
    planDetailReturnView=saved.ui.planReturnView;
    topicCalendarReturn=saved.ui.topicReturn;conflictCalendarReturn=saved.ui.conflictReturn;
    selectedEvent=saved.event.id;selectedDate=saved.event.date;viewDate=parseDate(saved.event.date);
    renderDetail();
    app.querySelector('.detail-scroll').scrollTop=saved.ui.scrollTop;
    const content=app.querySelector('.study-content');if(content)content.scrollTop=saved.ui.contentScrollTop;
    app.querySelector('[data-action="back"]')?.focus({preventScroll:true});
  },false,true,'restore');
  toast('학습 일정과 기록을 복구했어요.');
});
function openStudyDeletion(){
  const study=events.find(e=>e.id===selectedEvent && e.type==='study');if(!study)return;
  studyDeletionId=study.id;document.querySelector('#delete-study-name').textContent=study.title;
  showPhoneDialog('#delete-study');
}
document.querySelector('#cancel-delete-study').addEventListener('click',()=>closePhoneDialog('#delete-study'));
document.querySelector('#confirm-delete-study').addEventListener('click',async()=>{
  const id=studyDeletionId;if(!events.some(e=>e.id===id && e.type==='study'))return;
  await closePhoneDialog('#delete-study');
  const index=events.findIndex(e=>e.id===id);if(index<0)return;
  const snapshot={event:events[index],index,ui:{scrollTop:app.querySelector('.detail-scroll')?.scrollTop || 0,contentScrollTop:app.querySelector('.study-content')?.scrollTop || 0,returnView:eventDetailReturn,planReturnView:planDetailReturnView,topicReturn:topicCalendarReturn,conflictReturn:conflictCalendarReturn}};
  events=events.filter(e=>e.id!==id);studyDeletionId=null;
  renderPlanList();closeEventDetail();offerDeletedStudyUndo(snapshot);
});
function renderDetail(focusStatus) {
  hideQuickOverview();
  const scrollTop = focusStatus ? (app.querySelector('.detail-scroll')?.scrollTop || 0) : 0;
  const e = events.find(e => e.id === selectedEvent);
  if (!e) { selectedEvent = null; renderCalendar(); return; }
  const study = e.type === 'study', state = STATUS[e.status];
  app.innerHTML = `<article class="detail"><header class="toolbar">${iconButton('back', '달력으로 돌아가기', 'back')}<h2>${study ? '학습 일정' : '캘린더 일정'}</h2>${study?'<div class="study-detail-actions">'+iconButton('delete-study','학습 일정 삭제','trash')+'</div>':''}</header><div class="detail-scroll"><div class="detail-body"><div class="detail-heading">${study?renderPlanCertificateTarget(planFor(e)):'<p class="overline">연결된 캘린더</p>'}<h1>${escapeHTML(e.title)}</h1><span class="plan-tag">${study ? e.recoverySourceId?'보충 학습':'활성 학습 계획' : '기존 일정'}</span>${study ? `<span class="detail-status ${e.status}">${state.label}</span>` : ''}</div><section class="card" aria-label="일정 시간"><div class="time-row">${svg('calendar')}<p>${longDate(e.date)}</p></div><div class="time-row">${svg('clock')}<p>${timeLabel(e.start)} – ${timeLabel(e.end)}<small>${duration(e)}</small></p></div>${canEditSchedule(e)?'<button type="button" class="schedule-edit-button" data-action="edit-schedule">날짜·시간 수정</button>':'<p class="schedule-readonly-note">'+(study?'과거 일정은 기록으로 보관되어 날짜와 시간을 수정할 수 없어요.':'기존 캘린더 일정은 날짜와 시간을 수정할 수 없어요.')+'</p>'}</section><h2 class="section-heading">${study ? '학습 내용' : '일정 내용'}</h2><section class="card"><p class="study-content">${escapeHTML(e.content)}</p></section>${hasConflict(e) ? '<p class="conflict-note">이 학습 계획은 같은 시간의 다른 일정과 겹쳐요.<br>충돌 여부와 별개로 학습 결과를 기록할 수 있어요.</p>' : ''}${study ? `<section class="results"><h2 id="results-heading">얼마나 학습했나요?</h2><p class="result-note">${state.note}</p><div class="result-options" role="radiogroup" aria-labelledby="results-heading">${['done', 'partial', 'missed'].map((key, i) => `<button class="result-option" role="radio" aria-checked="${e.status === key}" tabindex="${e.status === key || (e.status === 'unknown' && i === 0) ? 0 : -1}" data-status="${key}" style="--accent:${STATUS[key].color}"><span class="radio" aria-hidden="true"></span>${STATUS[key].label}</button>`).join('')}</div></section>` : '<p class="mobile-help" style="padding:16px 8px">기존 캘린더 일정은 학습 결과를 기록하지 않습니다.</p>'}</div></div></article>`;
  app.querySelector('.conflict-note')?.remove();
  if (hasConflict(e)) {
    const conflicts = overlappingEvents(e);
    app.querySelector('.detail-body').insertAdjacentHTML('afterbegin', `<section class="conflict-banner" aria-labelledby="conflict-heading"><button class="conflict-dismiss" data-action="dismiss-conflict" aria-label="현재 일정 유지하고 충돌 해제">×</button><h2 id="conflict-heading">다른 일정과 겹쳐요</h2><p>${conflicts.map(c => `${escapeHTML(c.title)} · ${timeLabel(c.start)}–${timeLabel(c.end)}`).join('<br>')}</p>${isMovableStudy(e) ? '<p>학습 시간을 수정하거나, X를 눌러 현재 일정을 유지하세요.</p><div class="conflict-actions"><button data-action="manual-fix">직접 수정하기</button><button data-action="auto-fix">자동 수정하기</button></div>' : '<p>과거 학습 기록은 날짜와 시간을 수정할 수 없어요.</p>'}</section>`);
  }
  if (study) {
    const progress = progressForEvent(e);
    app.querySelector('.results').insertAdjacentHTML('beforebegin', `<section class="progress-card" aria-labelledby="progress-heading"><div class="progress-heading"><h3 id="progress-heading">학습 진행률</h3><span>새로고침 시 초기화</span></div><div class="progress-controls"><input id="progress-range" type="range" min="0" max="100" step="1" value="${progress}" aria-label="학습 진행률 막대" aria-valuetext="${progress}퍼센트" style="--progress:${progress}%"><label class="progress-value"><input id="progress-number" type="number" min="0" max="100" step="1" inputmode="numeric" value="${progress}" aria-label="학습 진행률 직접 입력"><span aria-hidden="true">%</span></label></div><p>막대를 움직이거나 숫자를 눌러 입력하세요.${e.workloadMinutes?` 진행률은 최초 배정 학습량 ${planTimeLabel(e.workloadMinutes)}을 기준으로 계산해요.`:''}</p></section>`);
  }
  if (focusStatus) {
    app.querySelector('.detail-scroll').scrollTop = scrollTop;
    app.querySelector(`[data-status="${focusStatus}"]`)?.focus({ preventScroll: true });
  }
}
let eventDetailReturn=null;
function openEvent(id) {
  const event=events.find(e=>e.id===id);
  if(!event || !isEventVisible(event))return;
  if(!eventDetailReturn || eventDetailReturn.id!==id){
    eventDetailReturn={id,calendarView,selectedDate,viewDate:new Date(viewDate),searchOpen,query:app.querySelector('#search-input')?.value || '',selectedPlan,selectedTopic,planTopicsOverview,planStatusOverview,monthAgendaExpanded,scrolls:['.time-scroll','.agenda-list','.detail-scroll','.plan-schedule-list','.topic-schedule-list','.search-results'].map(selector=>({selector,top:app.querySelector(selector)?.scrollTop || 0}))};
  }
  return animateDetailNavigation(()=>{
    selectedEvent=id;selectedDate=event.date;viewDate=parseDate(event.date);
    renderDetail();app.querySelector('[data-action="back"]')?.focus({preventScroll:true});
  },false,true);
}
function closeEventDetail(){
  const previous=eventDetailReturn,id=selectedEvent;eventDetailReturn=null;
  return animateDetailNavigation(()=>{
    selectedEvent=null;focusAfterRender=null;
    if(previous){
      ({calendarView,selectedDate,viewDate,searchOpen,selectedPlan,selectedTopic,planTopicsOverview,planStatusOverview,monthAgendaExpanded}=previous);
    }else searchOpen=false;
    renderCalendar();
    if(previous?.searchOpen){
      const input=app.querySelector('#search-input');if(input)input.value=previous.query;
      renderSearch(previous.query);
    }
    app.querySelector(`[data-event="${id}"]`)?.focus({preventScroll:true});
    previous?.scrolls.forEach(({selector,top})=>{const scroller=app.querySelector(selector);if(scroller)scroller.scrollTop=top;});
    if(previous?.todayPopup && events.some(e=>e.id===id)){
      openTodaySchedules();
      document.querySelector('#today-schedules').scrollTop=previous.todayPopupScrollTop || 0;
    }
  },true,true);
}
function setStatus(key) {
  const event = events.find(e => e.id === selectedEvent);
  if (!event || event.type !== 'study' || !Object.hasOwn(STATUS, key)) return;
  event.status = key;
  event.progress = key === 'done' ? 100 : key === 'missed' ? 0 : (event.progress > 0 && event.progress < 100 ? event.progress : 50);
  renderDetail(key);
  toast(`${STATUS[key].label}로 반영했어요`);
}
function dismissConflict() {
  const e = events.find(e => e.id === selectedEvent);
  if (!e || !hasConflict(e)) return;
  e.dismissedConflict = conflictSignature(e);
  renderDetail();
  app.querySelector('[data-action="back"]').focus();
  toast('현재 시간을 유지하고 충돌을 해제했어요');
}
const canEditSchedule=e=>Boolean(e && isEventVisible(e) && e.date>=TODAY && e.type==='study');
function scheduleEditRestriction(e,date){
  if(e?.type==='calendar')return '기존 캘린더 일정은 날짜와 시간을 수정할 수 없어요.';
  if(!canEditSchedule(e))return '과거 일정은 날짜와 시간을 수정할 수 없어요.';
  if(date<TODAY)return '오늘·미래 일정은 과거 날짜로 옮길 수 없어요.';
  return e.type==='study'?moveRestriction(e,date):'';
}
function openScheduleEditor() {
  const e = events.find(e => e.id === selectedEvent);
  if (!canEditSchedule(e)) return;
  const form = document.querySelector('#edit-form');
  form.elements.date.value = e.date; form.elements.start.value = e.start; form.elements.end.value = e.end;
  form.elements.date.min = TODAY; form.elements.date.max = e.type==='study'?examDateFor(e):'';
  document.querySelector('#edit-error').textContent = '';
  showPhoneDialog('#edit-schedule');
}
function startManualMove() {
  const e = events.find(e=>e.id===selectedEvent);
  if (!isMovableStudy(e)) return;
  calendarView='month';selectedEvent=null;searchOpen=false;
  viewDate=parseDate(e.date);selectedDate=e.date;
  renderCalendar();
  if(!openMovePreview(e.id,e.date))return;
  pendingMove.returnToDetail=true;
  pendingMove.awaitingTarget=true;
  movePicking=true;keyboardMove={id:e.id,date:e.date};
  document.querySelector('#move-summary').textContent=`${e.title} · 이동할 날짜를 선택해주세요`;
  if(!pendingMove.blockedDate)document.querySelector('#move-warning').textContent='날짜를 누르거나 깜빡이는 일정을 드래그해주세요. 확인 전에는 일정이 바뀌지 않아요.';
  document.querySelector('#confirm-move').disabled=true;
  app.querySelector('[data-reposition]')?.focus({preventScroll:true});
}
function applySchedule(e, schedule) {
  const restriction = scheduleEditRestriction(e, schedule.date);
  if (restriction) { toast(restriction); return false; }
  const changed = e.date !== schedule.date || e.start !== schedule.start || e.end !== schedule.end;
  Object.assign(e, schedule);
  if (changed) e.dismissedConflict = '';
  selectedDate = e.date; viewDate = parseDate(e.date);
  renderDetail();
  return true;
}
const minutesOfDay = time => Number(time.slice(0, 2)) * 60 + Number(time.slice(3));
const minutesToTime = minutes => `${pad(Math.floor(minutes / 60))}:${pad(minutes % 60)}`;
function findNextSlot(e) {
  if (!isMovableStudy(e)) return null;
  const length = minutesOfDay(e.end) - minutesOfDay(e.start);
  // Search the following seven days, 08:00–22:00, in 15-minute steps.
  // Never shift a plan earlier than its current time or into a past demo day.
  const firstDay = parseDate(e.date < TODAY ? TODAY : e.date);
  for (let offset = 0; offset < 7; offset++) {
    const day = new Date(firstDay); day.setDate(day.getDate() + offset);
    const date = dateKey(day);
    if (date > examDateFor(e)) break;
    const startAt = Math.max(8 * 60, date === e.date ? Math.ceil(minutesOfDay(e.start) / 15) * 15 : 8 * 60);
    for (let start = startAt; start + length <= 22 * 60; start += 15) {
      const end = start + length;
      const occupied = events.some(other => isEventVisible(other) && other.id !== e.id && other.date === date && minutesOfDay(other.start) < end && start < minutesOfDay(other.end));
      if (!occupied) return {date, start: minutesToTime(start), end: minutesToTime(end)};
    }
  }
  return null;
}
function autoFixSchedule() {
  const e = events.find(e => e.id === selectedEvent);
  if (!isMovableStudy(e) || !hasConflict(e)) return;
  const schedule = findNextSlot(e);
  if (!schedule) { toast('시험일 이내에 이동할 빈 시간이 없어요. 직접 수정해주세요.'); return; }
  calendarView='month';selectedEvent=null;searchOpen=false;
  viewDate=parseDate(e.date);selectedDate=e.date;
  renderCalendar();
  if(!openMovePreview(e.id,schedule.date,schedule))return;
  pendingMove.returnToDetail=true;
  pendingMove.autoSuggested=true;
  paintMovePreview(e,schedule.date,false,schedule);
  document.querySelector('#confirm-move').focus({preventScroll:true});
}
document.querySelector('#cancel-edit').addEventListener('click', () => closePhoneDialog('#edit-schedule'));
document.querySelector('#edit-form').addEventListener('submit', action => {
  action.preventDefault();
  const e = events.find(e => e.id === selectedEvent);
  if (!canEditSchedule(e)) return;
  const form = action.currentTarget;
  const schedule = {date: form.elements.date.value, start: form.elements.start.value, end: form.elements.end.value};
  if (!validSchedule(schedule)) { document.querySelector('#edit-error').textContent = '날짜를 확인하고 종료 시간을 시작 시간보다 늦게 선택해주세요.'; return; }
  const restriction = scheduleEditRestriction(e, schedule.date);
  if (restriction) { document.querySelector('#edit-error').textContent = restriction; return; }
  if (!applySchedule(e, schedule)) return;
  closePhoneDialog('#edit-schedule');
  app.querySelector('[data-action="back"]').focus();
  toast(hasConflict(e) ? '시간을 수정했지만 아직 다른 일정과 겹쳐요' : '학습 시간을 수정했어요');
});
let editingExamPlan=null;
function validExamDate(date) {
  return /^\d{4}-\d{2}-\d{2}$/.test(date) && !Number.isNaN(parseDate(date).getTime()) && dateKey(parseDate(date))===date;
}
function updateExamImpact() {
  const date=document.querySelector('#exam-date-input').value;
  const count=validExamDate(date)?events.filter(e=>e.type==='study' && e.planId===editingExamPlan && e.date>date).length:0;
  document.querySelector('#exam-impact').textContent=count
    ? `변경할 시험일 뒤에 학습 일정 ${count}개가 있어요. 시험일 변경 후 남은 일정의 조정 여부를 선택할 수 있어요.`
    : '학습 일정 이동 마감일도 바뀌어요. 시험일 변경 후 남은 일정의 조정 여부를 선택할 수 있어요.';
}
function openExamEditor(planId) {
  const plan=plans.find(plan=>plan.id===planId);
  if(!plan || pendingMove || pointerMove?.active)return;
  editingExamPlan=plan.id;
  document.querySelector('#exam-plan-name').textContent=plan.name;
  document.querySelector('#exam-date-input').value=plan.examDate;
  document.querySelector('#exam-error').textContent='';
  updateExamImpact();
  showPhoneDialog('#edit-exam');
}
async function saveExamDate() {
  const plan=plans.find(plan=>plan.id===editingExamPlan),date=document.querySelector('#exam-date-input').value;
  if(!plan || !validExamDate(date)){
    document.querySelector('#exam-error').textContent='올바른 시험일을 선택해주세요.';return false;
  }
  if(phoneDialogMotion.get(document.querySelector('#edit-exam'))?.closing)return false;
  const previousExamDate=plan.examDate;
  await closePhoneDialog('#edit-exam');
  plan.examDate=date;
  selectedDate=date;viewDate=parseDate(date);selectedEvent=null;searchOpen=false;
  renderCalendar();renderPlanList();
  toast('시험일을 변경했어요');
  if(previousExamDate!==date)offerPlanAdjustment(plan,previousExamDate);
  return true;
}

let pendingPlanAdjustment=null,planCalendarPreview=null,planDragInspection=null,planTimeEdit=null,planAdjustmentExit=null;
function remainingPlanEvents(planId) {
  return events.filter(e=>e.type==='study' && e.planId===planId && e.date>=TODAY && e.status!=='done')
    .sort((a,b)=>a.date.localeCompare(b.date)||a.start.localeCompare(b.start));
}
function offerPlanAdjustment(plan,previousExamDate) {
  const remaining=remainingPlanEvents(plan.id);
  if(!remaining.length)return;
  pendingPlanAdjustment={planId:plan.id,previousExamDate,examDate:plan.examDate,items:null};
  document.querySelector('#adjust-plan-heading').textContent='남은 학습 일정도 조정할까요?';
  document.querySelector('#adjust-plan-message').textContent=`${plan.name} 시험일을 ${longDate(plan.examDate)}로 변경했어요. 남은 학습 일정 ${remaining.length}개의 변경 예정 날짜를 먼저 확인할 수 있어요.`;
  document.querySelector('#preview-plan-adjustment').hidden=false;
  document.querySelector('#preview-plan-adjustment').disabled=plan.examDate<TODAY;
  document.querySelector('#keep-plan-schedule').textContent='그대로 두기';
  document.querySelector('#adjust-plan-note').textContent=plan.examDate<TODAY
    ? '시험일이 오늘보다 이전이라 남은 일정을 배치할 수 없어요. 기존 학습 일정을 유지합니다.'
    : '기존 학습 간격을 고려해 새 시험일까지 날짜를 조정해요. 학습 시간·진행률·완료 기록은 유지돼요.';
  showPhoneDialog('#adjust-plan');
}
async function previewPlanAdjustment() {
  const proposal=pendingPlanAdjustment;
  if(!proposal || proposal.examDate<TODAY)return;
  const dayNumber=date=>Math.round((parseDate(date)-parseDate(TODAY))/86400000);
  const oldDays=Math.max(1,dayNumber(proposal.previousExamDate)),newDays=dayNumber(proposal.examDate);
  proposal.items=remainingPlanEvents(proposal.planId).map(e=>{
    const offset=Math.round(Math.max(0,Math.min(1,dayNumber(e.date)/oldDays))*newDays);
    const date=parseDate(TODAY);date.setDate(date.getDate()+offset);
    return {id:e.id,title:e.title,before:{date:e.date,start:e.start,end:e.end},after:{date:dateKey(date),start:e.start,end:e.end}};
  });
  if(phoneDialogMotion.get(document.querySelector('#adjust-plan'))?.closing)return;
  await closePhoneDialog('#adjust-plan');
  proposal.returnView={calendarView,selectedDate,viewDate:new Date(viewDate)};
  planCalendarPreview=proposal;
  calendarView='month';selectedEvent=null;searchOpen=false;
  selectedDate=proposal.items.find(item=>item.before.date!==item.after.date)?.after.date || proposal.examDate;
  viewDate=parseDate(selectedDate);
  const phone=document.querySelector('.phone');
  phone.style.setProperty('--plan-preview-grid-height',`${Math.max(180,phone.clientHeight-319)}px`);
  phone.classList.add('plan-adjusting');
  document.querySelector('#plan-adjustment-preview').hidden=false;
  updatePlanCalendarSummary();
  renderCalendar();
  document.querySelector('#apply-plan-adjustment').focus({preventScroll:true});
}
function updatePlanCalendarSummary() {
  if(planTimeEdit){updatePlanTimeSummary();return;}
  const proposal=planCalendarPreview;
  if(!proposal)return;
  const ids=new Set(proposal.items.map(item=>item.id)),projected=calendarEvents();
  const conflicts=projected.filter(e=>ids.has(e.id) && projected.some(other=>(ids.has(other.id)||isEventVisible(other)) && other.id!==e.id && other.date===e.date && other.start<e.end && e.start<other.end)).length;
  const changed=proposal.items.filter(item=>['date','start','end'].some(key=>item.before[key]!==item.after[key])).length;
  const summary=document.querySelector('#plan-calendar-summary');
  summary.classList.remove('plan-drag-warning');
  summary.textContent=`변경 예정 ${changed}개 · ${conflicts?`시간 겹침 ${conflicts}개 · `:''}깜빡이는 일정은 드래그로 조정할 수 있어요. 마지막 확인을 눌러야 실제 일정이 바뀝니다.`;
  document.querySelector('#apply-plan-adjustment').disabled=false;
}
function clearPlanDraftTarget(keepSource=false) {
  app.querySelectorAll('.plan-drag-chip').forEach(el=>el.remove());
  app.querySelectorAll('.plan-time-drag-chip').forEach(el=>el.remove());
  if(!keepSource)app.querySelectorAll('.plan-preview-drag-source').forEach(el=>el.classList.remove('plan-preview-drag-source'));
  app.querySelectorAll('.move-date-unavailable').forEach(el=>el.classList.remove('move-date-unavailable'));
}
function syncMovingSource(e) {
  if(!e)return;
  app.querySelectorAll('.plan-preview-drag-source').forEach(el=>el.classList.remove('plan-preview-drag-source'));
  app.querySelector(`[data-event="${e.id}"]`)?.classList.add('plan-preview-drag-source');
}
function paintPlanDraftTarget(e,date) {
  if(!date)return;
  syncMovingSource(e);
  if(pointerMove?.showDetailsDuringDrag){
    document.querySelector('.phone').classList.add('plan-moving');
    if(!document.querySelector('#plan-drag-details').hidden)hidePlanDragDetails();
  }
  if(liveMove?.id!==e.id || liveMove?.date!==date || !document.querySelector('#move-preview').classList.contains('dragging'))paintMovePreview(e,date,true);
  const valid=canMoveToDate(e,date);
  markDropTarget(valid?date:null);
  if(!valid)app.querySelector(`[data-drop-date="${date}"]`)?.classList.add('drop-blocked');
}
function showPlanDragDetails(e,date) {
  if(!planCalendarPreview)return;
  const key=JSON.stringify([e?.id,date,e?.start,e?.end,planCalendarPreview.items.map(item=>[item.id,item.after.date,item.after.start,item.after.end])]);
  if(planDragInspection?.key===key)return;
  planDragInspection={id:e?.id || null,date,key};
  const candidate=e?{...e,date,isPreview:true}:null;
  const dayItems=[...byDate(date).filter(other=>other.id!==e?.id),...(candidate?[candidate]:[])].sort((a,b)=>a.start.localeCompare(b.start)||Number(a.isPreview||0)-Number(b.isPreview||0));
  const conflicts=new Set(dayItems.filter(other=>e?other.id!==e.id && other.start<e.end && e.start<other.end:dayItems.some(item=>item.id!==other.id && item.start<other.end && other.start<item.end)).map(other=>other.id));
  const valid=e?canMoveToDate(e,date):true;
  const phone=document.querySelector('.phone');
  phone.style.setProperty('--plan-drag-grid-height',`${Math.max(130,phone.clientHeight/2-155)}px`);
  phone.classList.add('plan-inspecting');
  document.querySelector('#toggle-plan-details').setAttribute('aria-expanded','true');
  document.querySelector('#toggle-plan-details').setAttribute('aria-label','상세 일정 접기');
  document.querySelector('#plan-drag-details').hidden=false;
  document.querySelector('#plan-drag-heading').textContent=longDate(date);
  const status=document.querySelector('#plan-drag-status');
  status.classList.toggle('plan-drag-warning',!valid || conflicts.size>0);
  status.textContent=!valid
    ? `${date<TODAY?'과거 날짜':'새 시험일 이후'}로는 옮길 수 없어요.${conflicts.size?' 같은 시간에 다른 일정도 있어요.':''}`
    : conflicts.size?'같은 시간에 다른 일정이 있어요.':'겹치는 일정이 없어요.';
  document.querySelector('#plan-drag-day-events').innerHTML=dayItems.map(item=>{
    const overlap=item.isPreview?conflicts.size>0:conflicts.has(item.id);
    const badge=item.isPreview?`<span class="move-item-badge">${valid?'이동 예정':'이동 불가'}</span>`:'';
    const editable=overlap && item.type==='study' && item.status!=='done' && item.date>=TODAY;
    const tag=editable?'button':'article',attributes=editable?`type="button" data-plan-time-edit="${item.id}" aria-label="${escapeHTML(item.title)} 시간 조정"`:'';
    return `<${tag} ${attributes} class="move-day-item ${editable?'plan-time-editable':''} ${item.isPreview?'proposed-move-item':''} ${overlap?'conflicting-move-item':''}"><span class="move-item-color ${eventClass(item)}" aria-hidden="true"></span><div><h3><span class="move-item-title">${escapeHTML(item.title)}</span>${badge}</h3><p>${timeLabel(item.start)} – ${timeLabel(item.end)}</p><small>${escapeHTML(item.content)}</small>${editable?'<small class="plan-time-link">눌러서 시간 조정</small>':''}</div></${tag}>`;
  }).join('') || '<p class="empty">이 날짜에는 일정이 없어요.</p>';
  scrollToMoveConflicts('#plan-drag-day-events','#plan-drag-details');
}
function hidePlanDragDetails() {
  planDragInspection=null;
  document.querySelector('#plan-drag-details').hidden=true;
  document.querySelector('.phone').classList.remove('plan-inspecting');
  document.querySelector('#toggle-plan-details').setAttribute('aria-expanded','false');
  document.querySelector('#toggle-plan-details').setAttribute('aria-label','상세 일정 펼치기');
  if(movePreviewScrollFrame!==null){cancelAnimationFrame(movePreviewScrollFrame);movePreviewScrollFrame=null;}
}
function startPlanTimeEdit(id) {
  if(!planCalendarPreview || planTimeEdit || pointerMove)return;
  const e=calendarEvents().find(e=>e.id===id);
  if(!e || e.type!=='study' || e.status==='done' || e.date<TODAY || !overlappingEvents(e).length)return;
  const itemsBefore=planCalendarPreview.items.map(item=>({...item,before:{...item.before},after:{...item.after}}));
  let item=planCalendarPreview.items.find(item=>item.id===id);
  const addedItem=!item;
  if(!item){
    item={id:e.id,title:e.title,before:{date:e.date,start:e.start,end:e.end},after:{date:e.date,start:e.start,end:e.end}};
    planCalendarPreview.items.push(item);
  }
  planTimeEdit={id,before:{...item.after},draft:{...item.after},addedItem,itemsBefore,returnView:{viewDate:new Date(viewDate),selectedDate},inspection:planDragInspection?{id:planDragInspection.id,date:planDragInspection.date}:null};
  hidePlanDragDetails();
  calendarView='day';selectedDate=e.date;viewDate=parseDate(e.date);
  document.querySelector('.phone').classList.add('plan-time-editing');
  document.querySelector('#plan-calendar-heading').textContent='학습 시간 조정';
  renderCalendar();updatePlanTimeSummary();
  const scroll=app.querySelector('.time-scroll');if(scroll)scroll.scrollTop=Math.max(0,(minutesOfDay(e.start)-60)*timelineScale);
}
function selectPlanTimeTarget(e) {
  if(planTimeEdit.id===e.id)return;
  let item=planCalendarPreview.items.find(item=>item.id===e.id);
  const addedItem=!item;
  if(!item){
    item={id:e.id,title:e.title,before:{date:e.date,start:e.start,end:e.end},after:{date:e.date,start:e.start,end:e.end}};
    planCalendarPreview.items.push(item);
  }
  Object.assign(planTimeEdit,{id:e.id,before:{...item.after},draft:{...item.after},addedItem});
  updatePlanTimeSummary();
}
function updatePlanTimeSummary(schedule=planTimeEdit?.draft) {
  if(!planTimeEdit || !schedule)return;
  const before=planTimeEdit.before;
  const overlap=calendarEvents().some(e=>e.id!==planTimeEdit.id && (isEventVisible(e)||e.isPlanAdjustmentPreview) && e.date===schedule.date && e.start<schedule.end && schedule.start<e.end);
  const summary=document.querySelector('#plan-calendar-summary');
  summary.classList.toggle('plan-drag-warning',overlap);
  summary.textContent=`${before.start}–${before.end} → ${schedule.start}–${schedule.end}${overlap?' · 같은 시간에 다른 일정이 있어요.':''} 다른 일정도 연속으로 조정할 수 있어요. 마지막 확인은 미리보기에만 반영돼요.`;
  document.querySelector('#apply-plan-adjustment').disabled=Boolean(pointerMove?.active);
}
function paintPlanTimeTarget(e,schedule) {
  if(!schedule)return;
  syncMovingSource(e);
  if(pointerMove?.showDetailsDuringDrag)document.querySelector('.phone').classList.add('plan-moving');
  if(liveMove?.id!==e.id || liveMove?.date!==schedule.date || liveMove?.start!==schedule.start || !document.querySelector('#move-preview').classList.contains('dragging'))paintMovePreview(e,schedule.date,true,schedule);
  markDropTarget(schedule.date);
  const label=pointerMove?.ghost?.querySelector('.timed-event-time');if(label)label.textContent=`${schedule.start}–${schedule.end}`;
}
function finishPlanTimeEdit(apply) {
  if(!planTimeEdit || (apply && pointerMove))return;
  if(pointerMove?.planAdjustment)clearPointerMove();
  const edit=planTimeEdit,item=planCalendarPreview.items.find(item=>item.id===edit.id);
  if(apply){item.after={...edit.draft};item.manuallyAdjusted=true;item.manualTimeEdit=true;}
  else planCalendarPreview.items=edit.itemsBefore;
  planTimeEdit=null;calendarView='month';selectedDate=edit.returnView.selectedDate;viewDate=edit.returnView.viewDate;
  document.querySelector('.phone').classList.remove('plan-time-editing');
  document.querySelector('#plan-calendar-heading').textContent='학습 일정 변경 미리보기';
  renderCalendar();updatePlanCalendarSummary();
  if(edit.inspection){
    const e=calendarEvents().find(e=>e.id===edit.inspection.id);
    if(e || !edit.inspection.id)showPlanDragDetails(e || null,edit.inspection.date);
  }
}
document.querySelector('#plan-drag-day-events').addEventListener('click',event=>{
  const button=event.target.closest('[data-plan-time-edit]');
  if(button?.dataset.planTimeEdit)startPlanTimeEdit(button.dataset.planTimeEdit);
});
function endPlanCalendarPreview(restoreView=false) {
  stopSheetResizeMotion();
  document.querySelector('#plan-adjustment-preview').classList.remove('sheet-interacted');
  if(pointerMove?.planAdjustment)clearPointerMove();
  hidePlanDragDetails();
  const proposal=planCalendarPreview;
  planCalendarPreview=null;
  planTimeEdit=null;
  document.querySelector('#plan-adjustment-preview').hidden=true;
  document.querySelector('.phone').classList.remove('plan-adjusting');
  document.querySelector('.phone').classList.remove('plan-time-editing');
  if(restoreView && proposal?.returnView){
    calendarView=proposal.returnView.calendarView;selectedDate=proposal.returnView.selectedDate;viewDate=proposal.returnView.viewDate;
  }
}
function cancelPlanAdjustment() {
  if(planAdjustmentExit)return planAdjustmentExit;
  if(!planCalendarPreview)return;
  if(pendingMove)return cancelMove();
  if(planTimeEdit){finishPlanTimeEdit(false);return;}
  return exitPlanAdjustment(false);
}
async function applyPlanAdjustment() {
  if(planAdjustmentExit)return planAdjustmentExit;
  if(pendingMove)return;
  if(planTimeEdit){finishPlanTimeEdit(true);return;}
  const proposal=planCalendarPreview;
  if(!proposal?.items || pointerMove?.planAdjustment)return;
  const plan=plans.find(plan=>plan.id===proposal.planId);
  if(plan?.examDate!==proposal.examDate || proposal.items.some(item=>{
    const e=events.find(e=>e.id===item.id);
    return !e || (e.status==='done' && !item.manualTimeEdit) || !['date','start','end'].every(key=>e[key]===item.before[key]);
  })){toast('일정이 변경됐어요. 변경 예정 일정을 다시 확인해주세요.');return;}
  return exitPlanAdjustment(true);
}
function exitPlanAdjustment(apply) {
  if(planAdjustmentExit)return planAdjustmentExit;
  const proposal=planCalendarPreview;
  if(!proposal)return Promise.resolve();
  const phone=document.querySelector('.phone'),controls=document.querySelector('#plan-adjustment-preview');
  const surface=app.querySelector('.grid'),previousInert=Boolean(app.inert);
  const targetView=apply?'month':proposal.returnView?.calendarView || 'month';
  const reduced=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const finish=()=>{
    if(apply)proposal.items.forEach(item=>{
      const e=events.find(e=>e.id===item.id);
      if(['date','start','end'].some(key=>e[key]!==item.after[key])){Object.assign(e,item.after);e.dismissedConflict='';}
    });
    endPlanCalendarPreview(!apply);
    phone.classList.remove('plan-final-closing');
    renderCalendar();renderPlanList();
    app.inert=previousInert;controls.inert=false;
    document.querySelector('#apply-plan-adjustment').disabled=false;
    document.querySelector('#cancel-plan-adjustment').disabled=false;
    if(!reduced)app.animate?.([{opacity:.8},{opacity:1}],{duration:160,easing:'ease-out'});
    toast(apply?'남은 학습 일정을 새 시험일에 맞춰 조정했어요':'시험일은 유지하고 학습 일정 조정을 취소했어요');
    planAdjustmentExit=null;
  };
  if(reduced || !surface?.animate || !controls.animate){finish();return Promise.resolve();}
  const currentHeight=surface.getBoundingClientRect().height;
  // Measure the normal view with its responsive CSS, outside preview modifiers.
  const probe=document.createElement('div');probe.className=targetView==='month'?'grid':targetView==='year'?'year-view':'time-view';
  probe.style.cssText='position:fixed;visibility:hidden;pointer-events:none;width:1px';
  document.body.append(probe);
  const targetHeight=probe.getBoundingClientRect?.().height || (targetView==='month'?560:phone.clientHeight-120);
  probe.remove();
  const animations=[],options={duration:340,easing:'cubic-bezier(.4,0,.7,.3)',fill:'forwards'};
  const pulseFrames=[...app.querySelectorAll('.plan-adjustment-event')].map(el=>({el,style:getComputedStyle(el)}));
  phone.style.setProperty('--plan-exit-grid-height',`${targetHeight}px`);
  phone.classList.add('plan-final-closing');
  app.inert=true;controls.inert=true;
  document.querySelector('#apply-plan-adjustment').disabled=true;
  document.querySelector('#cancel-plan-adjustment').disabled=true;
  animations.push(surface.animate([{height:`${currentHeight}px`},{height:`${targetHeight}px`}],options));
  const current=getComputedStyle(controls);
  animations.push(controls.animate([{transform:current.transform,opacity:current.opacity},{transform:'translateY(100%)',opacity:0}],options));
  pulseFrames.forEach(({el,style})=>{
    if(el.animate)animations.push(el.animate([{opacity:style.opacity,outlineColor:style.outlineColor || '#37855a'},{opacity:apply?1:0,outlineColor:'transparent'}],options));
  });
  if(targetView==='month')app.querySelectorAll('.agenda,.fab').forEach(el=>{
    if(el.animate)animations.push(el.animate([{opacity:0,transform:'translateY(10px)'},{opacity:1,transform:'translateY(0)'}],options));
  });
  planAdjustmentExit=Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(()=>{
    finish();animations.forEach(animation=>animation.cancel());
  });
  return planAdjustmentExit;
}
document.querySelector('#preview-plan-adjustment').addEventListener('click',previewPlanAdjustment);
document.querySelector('#apply-plan-adjustment').addEventListener('click',applyPlanAdjustment);
document.querySelector('#cancel-plan-adjustment').addEventListener('click',cancelPlanAdjustment);
document.querySelector('#plan-adjustment-preview').addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();cancelPlanAdjustment();}});
document.querySelector('#keep-plan-schedule').addEventListener('click',()=>closePhoneDialog('#adjust-plan'));
document.querySelector('#close-plan-adjustment').addEventListener('click',()=>closePhoneDialog('#adjust-plan'));
document.querySelector('#adjust-plan').addEventListener('close',()=>{pendingPlanAdjustment=null;});
document.querySelector('#exam-date-input').addEventListener('input',()=>{
  document.querySelector('#exam-error').textContent='';updateExamImpact();
});
document.querySelector('#exam-form').addEventListener('submit',event=>{event.preventDefault();return saveExamDate();});
document.querySelector('#cancel-exam').addEventListener('click',()=>closePhoneDialog('#edit-exam'));
document.querySelector('#close-exam').addEventListener('click',()=>closePhoneDialog('#edit-exam'));
const planTimeLabel=minutes=>{const rounded=Math.round(minutes);return rounded>=60?`${Math.floor(rounded/60)}시간${rounded%60?` ${rounded%60}분`:''}`:`${rounded}분`;};
function planStatistics(id){
  const studies=events.filter(e=>e.type==='study' && e.planId===id),topics=new Map();
  for(const event of studies){
    const name=event.topic || event.title,minutes=studyWorkloadMinutes(event);
    if(!topics.has(name))topics.set(name,{name,minutes:0,completedMinutes:0,expectedMinutes:0,count:0,done:0});
    const topic=topics.get(name);topic.minutes+=minutes;topic.completedMinutes+=minutes*progressForEvent(event)/100;topic.count++;if(event.status==='done')topic.done++;
    if(event.date<=TODAY)topic.expectedMinutes+=minutes;
  }
  const rows=[...topics.values()].map(topic=>({...topic,progress:topic.minutes?Math.round(topic.completedMinutes/topic.minutes*100):0,expectedProgress:topic.minutes?Math.round(topic.expectedMinutes/topic.minutes*100):0}));
  const minutes=rows.reduce((sum,row)=>sum+row.minutes,0),completedMinutes=rows.reduce((sum,row)=>sum+row.completedMinutes,0);
  const expectedMinutes=studies.filter(e=>e.date<=TODAY).reduce((sum,e)=>sum+studyWorkloadMinutes(e),0);
  return {studies,topics:rows,minutes,completedMinutes,expectedMinutes,expectedProgress:minutes?Math.round(expectedMinutes/minutes*100):0,progress:minutes?Math.round(completedMinutes/minutes*100):0,done:studies.filter(e=>e.status==='done').length};
}
async function openPlanDetail(id){
  diagnosisRecordsReturn=null;
  if(!plans.some(plan=>plan.id===id))return;
  await closePlansDrawer();
  eventDetailReturn=null;
  return animateDetailNavigation(()=>{
    planDetailReturnView={calendarView,selectedDate,viewDate:new Date(viewDate),searchOpen,timeScrollTop:app.querySelector('.time-scroll')?.scrollTop || 0,agendaScrollTop:app.querySelector('.agenda-list')?.scrollTop || 0,searchScrollTop:app.querySelector('.search-results')?.scrollTop || 0};
    conflictCalendarReturn=null;topicCalendarReturn=null;selectedPlan=id;selectedTopic=null;topicStatusFilter=null;planStatusFilter=null;
    planSchedulesExpanded=false;planTopicsOverview=false;planStatusOverview=false;planSectionReturn=null;topicDetailReturn=null;
    selectedEvent=null;searchOpen=false;renderCalendar();app.querySelector('[data-action="plan-back"]')?.focus({preventScroll:true});
  },false,true);
}
function closePlanDetail(){
  return animateDetailNavigation(()=>{
    const previous=planDetailReturnView;
    planTopicsOverview=false;planStatusOverview=false;planSectionReturn=null;topicDetailReturn=null;
    selectedPlan=null;selectedTopic=null;topicStatusFilter=null;planStatusFilter=null;selectedEvent=null;
    if(planDetailReturnView){({calendarView,selectedDate,viewDate,searchOpen}=planDetailReturnView);planDetailReturnView=null;}
    renderCalendar();
    if(previous){
      for(const [selector,key] of [['.time-scroll','timeScrollTop'],['.agenda-list','agendaScrollTop'],['.search-results','searchScrollTop']]){
        const scroller=app.querySelector(selector);if(scroller)scroller.scrollTop=previous[key] || 0;
      }
    }
  },true,true);
}
let managingPlanId=null,deletedPlanUndo=null,deletedPlanUndoTimer=null;
function clearDeletedPlanUndo(){
  clearTimeout(deletedPlanUndoTimer);deletedPlanUndoTimer=null;deletedPlanUndo=null;
  const notice=document.querySelector('#plan-delete-undo');notice.classList.remove('visible');notice.inert=true;notice.setAttribute('aria-hidden','true');
}
function offerDeletedPlanUndo(snapshot){
  clearDeletedStudyUndo();
  clearDeletedPlanUndo();deletedPlanUndo=snapshot;
  const notice=document.querySelector('#plan-delete-undo');notice.inert=false;notice.setAttribute('aria-hidden','false');notice.classList.add('visible');
  deletedPlanUndoTimer=setTimeout(function expireDeletedPlanUndo(){clearDeletedPlanUndo();},8000);
}
document.querySelector('#undo-plan-delete').addEventListener('click',async()=>{
  const saved=deletedPlanUndo;if(!saved)return;
  clearDeletedPlanUndo();
  if(!plans.some(plan=>plan.id===saved.plan.id))plans.splice(Math.min(saved.index,plans.length),0,saved.plan);
  saved.events.forEach(({event,index})=>{if(!events.some(existing=>existing.id===event.id))events.splice(Math.min(index,events.length),0,event);});
  renderPlanList();await closePlansDrawer();
  animateDetailNavigation(()=>{
    if(planCreation)cancelPlanCreation();
    selectedPlan=saved.plan.id;selectedTopic=null;selectedEvent=null;eventDetailReturn=null;searchOpen=false;
    planTopicsOverview=false;planStatusOverview=false;planSectionReturn=null;topicDetailReturn=null;
    topicCalendarReturn=null;conflictCalendarReturn=null;focusAfterRender=null;
    planStatusFilter=saved.ui.filter;planSchedulesExpanded=saved.ui.expanded;
    planDetailReturnView=saved.ui.returnView;
    renderCalendar();
    app.querySelector('.detail-scroll').scrollTop=saved.ui.scrollTop;
    const list=app.querySelector('.plan-schedule-list');if(list)list.scrollTop=saved.ui.listScrollTop;
    app.querySelector('[data-action="plan-back"]')?.focus({preventScroll:true});
  },false,true);
  toast('계획과 학습 일정·기록·시험일을 복구했어요');
});
function openPlanManagement(kind){
  const plan=plans.find(plan=>plan.id===selectedPlan);if(!plan)return;
  managingPlanId=plan.id;
  if(kind==='rename'){
    document.querySelector('#plan-name-input').value=plan.name;
    document.querySelector('#plan-name-error').textContent='';
    showPhoneDialog('#rename-plan');document.querySelector('#plan-name-input').select?.();
  }else{
    const count=events.filter(e=>e.type==='study' && e.planId===plan.id).length;
    document.querySelector('#delete-plan-message').textContent=`‘${plan.name}’ 계획을 삭제할까요? 학습 기록을 포함한 ${count}개의 학습 일정과 시험일도 함께 삭제됩니다. 삭제 후 8초 동안 실행 취소할 수 있어요.`;
    showPhoneDialog('#delete-plan');
  }
}
document.querySelector('#cancel-plan-name').addEventListener('click',()=>closePhoneDialog('#rename-plan'));
document.querySelector('#cancel-delete-plan').addEventListener('click',()=>closePhoneDialog('#delete-plan'));
document.querySelector('#plan-name-form').addEventListener('submit',async event=>{
  event.preventDefault();
  const plan=plans.find(plan=>plan.id===managingPlanId),name=document.querySelector('#plan-name-input').value.trim();
  if(!plan)return;
  if(!name || name.length>40){document.querySelector('#plan-name-error').textContent='계획 이름을 1~40자로 입력해주세요.';return;}
  const scroll=app.querySelector('.detail-scroll')?.scrollTop || 0;
  await closePhoneDialog('#rename-plan');
  plan.name=name;renderPlanList();renderCalendar();app.querySelector('.detail-scroll').scrollTop=scroll;
  toast('계획 이름을 변경했어요');
});
document.querySelector('#confirm-delete-plan').addEventListener('click',async()=>{
  const id=managingPlanId;if(!plans.some(plan=>plan.id===id))return;
  await closePhoneDialog('#delete-plan');
  const index=plans.findIndex(plan=>plan.id===id);if(index<0)return;
  const snapshot={plan:plans[index],index,ui:{scrollTop:app.querySelector('.detail-scroll')?.scrollTop || 0,listScrollTop:app.querySelector('.plan-schedule-list')?.scrollTop || 0,filter:planStatusFilter,expanded:planSchedulesExpanded,returnView:planDetailReturnView?{...planDetailReturnView,viewDate:new Date(planDetailReturnView.viewDate)}:null},events:events.map((event,index)=>({event,index})).filter(({event})=>event.type==='study' && event.planId===id)};
  const deleted=new Set(snapshot.events.map(({event})=>event.id));
  plans=plans.filter(plan=>plan.id!==id);events=events.filter(e=>!deleted.has(e.id));
  if(deleted.has(calendarSpotlight)){calendarSpotlight=null;clearTimeout(calendarSpotlightTimer);}
  eventDetailReturn=null;closePlanDetail();renderPlanList();offerDeletedPlanUndo(snapshot);
});
function renderProgressTrack(stats,label='실제 학습 진행률'){
  return `<div class="plan-progress-track" style="--expected-progress:${stats.expectedProgress}%"><progress value="${stats.progress}" max="100" aria-label="${escapeHTML(label)} ${stats.progress}퍼센트"></progress><span class="plan-progress-reference" role="img" aria-label="예상 학습 진행률 ${stats.expectedProgress}퍼센트 기준선" title="예상 학습 진행률 ${stats.expectedProgress}%"></span></div>`;
}
function renderProgressComparison(stats,label='전체 학습 진행률',{compact=false}={}){
  return `${compact?'':`<div><span>${escapeHTML(label)}</span><strong>${stats.progress}%</strong></div>`}${renderProgressTrack(stats,label)}${stats.progress<stats.expectedProgress?`<p class="plan-progress-lag" role="status">예상보다 ${stats.expectedProgress-stats.progress}%p 뒤처져 있어요.</p>`:stats.progress>stats.expectedProgress?`<p class="plan-progress-lead" role="status">예상보다 ${stats.progress-stats.expectedProgress}%p 앞서 있어요.</p>`:'<p class="plan-progress-on-track" role="status">계획대로 진행하고 있어요.</p>'}<div class="plan-expected-progress"><span>예상 학습 진행률</span><strong>${stats.expectedProgress}%</strong></div>${compact?'':'<p class="expected-progress-note">오늘까지 배정된 학습 시간 ÷ 전체 배정 시간 기준이에요.</p>'}`;
}
function isPlanBehind(id){const stats=planStatistics(id);return stats.progress<stats.expectedProgress;}
function renderTopicCards(stats){
  return `<h2 class="section-heading">학습 주제별 현황</h2><p class="plan-stats-note">일정 길이에 기록한 학습 진행률을 반영해 계산해요. 미확인 일정은 0%로 계산합니다.</p><div class="plan-topic-list">${stats.topics.length?stats.topics.map(topic=>`<section class="card plan-topic" data-plan-topic="${escapeHTML(topic.name)}"><button type="button" class="plan-topic-open" data-plan-topic="${escapeHTML(topic.name)}" aria-label="${escapeHTML(topic.name)} 상태와 일정 보기"><span>${escapeHTML(topic.name)}</span><strong>${topic.progress}% <span aria-hidden="true">›</span></strong></button><div class="plan-overview topic-progress-comparison">${renderProgressComparison(topic,topic.name+' 학습 진행률',{compact:true})}</div><dl><div><dt>배정 시간</dt><dd>${planTimeLabel(topic.minutes)}</dd></div><div><dt>학습 반영 시간</dt><dd>${planTimeLabel(topic.completedMinutes)}</dd></div></dl><p>${topic.count}개 일정 중 ${topic.done}개 완료</p></section>`).join(''):'<p class="empty">아직 배정된 학습 일정이 없어요.</p>'}</div>`;
}
function renderAllocationDonut(stats,colors){
  const circumference=2*Math.PI*66;let offset=0;
  const slices=stats.topics.filter(topic=>topic.minutes>0).map(topic=>{
    const index=stats.topics.indexOf(topic),share=stats.minutes?topic.minutes/stats.minutes:0,start=offset;offset+=share;
    const label=topic.name+' '+planTimeLabel(topic.minutes)+', '+Math.round(share*100)+'%';
    return `<circle class="allocation-donut-slice" cx="90" cy="90" r="66" fill="none" stroke="${colors[index%colors.length]}" stroke-width="24" stroke-dasharray="${share*circumference} ${circumference}" stroke-dashoffset="${-start*circumference}" transform="rotate(-90 90 90)" data-plan-topic="${escapeHTML(topic.name)}" role="button" tabindex="0" aria-label="${escapeHTML(label)} 학습 주제 상세 보기"><title>${escapeHTML(label)}</title></circle>`;
  }).join('');
  return `<div class="allocation-donut"><svg viewBox="0 0 180 180" role="group" aria-label="학습 주제별 시간 배정 원 그래프"><circle cx="90" cy="90" r="66" fill="none" stroke="#edf0ee" stroke-width="24"/>${slices}</svg><span class="allocation-donut-total"><small>전체 배정 시간</small><strong>${planTimeLabel(stats.minutes)}</strong></span></div>`;
}
function renderTopicAllocation(stats,{detailsLink=true}={}){
  const colors=['#559c6d','#668db7','#b7985b','#987db3','#c17f7f','#77a6a4'];
  const accessibleLabel='학습 주제별 배정 시간: '+(stats.topics.map(topic=>`${topic.name} ${planTimeLabel(topic.minutes)}`).join(', ') || '배정된 학습 일정 없음');
  return `<section class="card topic-allocation"><header class="allocation-heading"><h2>학습 주제별 시간 배정</h2>${detailsLink?'<button type="button" data-action="topic-allocation-details">자세히 보기</button>':''}</header><p class="allocation-total">전체 배정 시간 <strong>${planTimeLabel(stats.minutes)}</strong></p>${renderAllocationDonut(stats,colors)}<div class="allocation-chart" role="group" aria-label="${escapeHTML(accessibleLabel)}">${stats.topics.length?stats.topics.map((topic,index)=>{const percent=stats.minutes?Math.round(topic.minutes/stats.minutes*100):0;return `<button type="button" class="allocation-row" data-plan-topic="${escapeHTML(topic.name)}" aria-label="${escapeHTML(topic.name)} 학습 주제 상세 보기, ${planTimeLabel(topic.minutes)}, ${percent}%"><span class="allocation-summary"><span>${escapeHTML(topic.name)}</span><strong>${planTimeLabel(topic.minutes)} <small>· ${percent}%</small></strong></span><span class="allocation-track"><span style="width:${stats.minutes?topic.minutes/stats.minutes*100:0}%;background:${colors[index%colors.length]}"></span></span></button>`;}).join(''):'<p class="empty">아직 배정된 학습 일정이 없어요.</p>'}</div></section>`;
}
function renderPlanTopicsOverview(plan){
  const stats=planStatistics(plan.id);
  app.innerHTML=`<article class="detail plan-detail plan-allocation-detail"><header class="toolbar">${iconButton('allocation-back','계획 상세로 돌아가기','back')}<h2>학습 주제별 시간 배정</h2></header><div class="detail-scroll"><div class="detail-body"><div class="detail-heading">${renderPlanCertificateTarget(plan)}${plan.name!==planCertificate(plan).name?`<p class="overline">${escapeHTML(plan.name)}</p>`:''}</div>${renderTopicAllocation(stats,{detailsLink:false})}${renderTopicCards(stats)}</div></div></article>`;
}
let detailNavigationMotion=null;
function animateDetailNavigation(change,backward=false,fullPage=false,motionStyle='slide'){
  clearMonthAgendaContentMotion();
  detailNavigationMotion?.finish();
  for(const stop of [...detailListMotions])stop();
  const reduced=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const outgoing=fullPage?app:app.querySelector('.detail'),phone=document.querySelector('.phone');
  const canAnimate=!reduced && outgoing?.cloneNode && outgoing?.getBoundingClientRect && outgoing?.animate && phone?.getBoundingClientRect;
  const rect=canAnimate?outgoing.getBoundingClientRect():null,frame=canAnimate?phone.getBoundingClientRect():null;
  const ghost=canAnimate?outgoing.cloneNode(true):null;
  const scrolls=ghost?['.detail-scroll','.plan-schedule-list','.topic-schedule-list','.time-scroll','.agenda-list','.search-results','.creation-scroll','.creation-time-scroll'].map(selector=>({selector,top:outgoing.querySelector(selector)?.scrollTop || 0})):null;
  const sizes=ghost && fullPage?['.grid','.agenda','.time-view','.year-view'].map(selector=>({selector,height:outgoing.querySelector(selector)?.getBoundingClientRect?.().height})):[];
  if(ghost && fullPage && phone.classList.contains('creation-preview-active')){
    ghost.classList.add('creation-preview-active');ghost.style.display='flex';ghost.style.flexDirection='column';
    const targets=ghost.querySelectorAll('[data-replan-event-group]');
    outgoing.querySelectorAll('[data-replan-event-group]').forEach((node,index)=>{if(!targets[index])return;const style=getComputedStyle(node);targets[index].style.setProperty('animation','none','important');targets[index].style.opacity=style.opacity;targets[index].style.filter=style.filter;targets[index].style.boxShadow=style.boxShadow;});
  }
  const diagnosisMotion=motionStyle==='diagnosis' || motionStyle==='history' || motionStyle==='diagnosis-list';
  if(diagnosisMotion)app.classList.add('diagnosis-navigation');
  change();
  const incoming=fullPage?app:app.querySelector('.detail');
  if(!ghost || !incoming?.animate){if(diagnosisMotion)app.classList.remove('diagnosis-navigation');return;}
  ghost.removeAttribute('id');ghost.querySelectorAll('[id]').forEach(node=>node.removeAttribute('id'));
  if(fullPage && motionStyle==='fade')ghost.querySelectorAll('.fab,.quick-overview-button,.self-diagnosis-button').forEach(node=>node.remove());
  if(fullPage){ghost.classList.add('calendar-navigation-ghost');sizes.forEach(({selector,height})=>{const node=ghost.querySelector(selector);if(node && Number.isFinite(height)){node.style.height=height+'px';node.style.minHeight='0';}});}
  ghost.classList.add('detail-navigation-ghost');ghost.inert=true;ghost.setAttribute('aria-hidden','true');
  Object.assign(ghost.style,{position:'absolute',left:`${rect.left-frame.left}px`,top:`${rect.top-frame.top}px`,width:`${rect.width}px`,height:`${rect.height}px`,margin:'0',pointerEvents:'none',zIndex:'6'});
  phone.append(ghost);
  scrolls.forEach(({selector,top})=>{const scroller=ghost.querySelector(selector);if(scroller)scroller.scrollTop=top;});
  const restoring=motionStyle==='restore' || diagnosisMotion,fading=motionStyle==='fade',recordsMotion=motionStyle==='records';
  const direction=backward?-1:1,verticalDirection=diagnosisMotion?direction:1,options={duration:recordsMotion?320:['history','diagnosis-list'].includes(motionStyle)?340:diagnosisMotion?320:restoring?360:300,easing:'cubic-bezier(.22,.75,.18,1)',fill:'both'};
  const animations=[
    ghost.animate(fading?[{opacity:1},{opacity:0}]:[{opacity:1,transform:restoring?'translateY(0)':'translateX(0)'},{opacity:0,transform:restoring?`translateY(${-6*verticalDirection}px)`:`translateX(${-direction*(recordsMotion?12:20)}px)`}],options),
    incoming.animate(fading?[{opacity:0},{opacity:1}]:[{opacity:0,transform:restoring?`translateY(${16*verticalDirection}px)`:`translateX(${direction*(recordsMotion?20:32)}px)`},{opacity:1,transform:restoring?'translateY(0)':'translateX(0)'}],options)
  ];
  let finished=false;
  const motion={finish(){if(finished)return;finished=true;animations.forEach(animation=>animation.cancel());ghost.remove();if(diagnosisMotion)app.classList.remove('diagnosis-navigation');if(detailNavigationMotion===motion)detailNavigationMotion=null;}};
  detailNavigationMotion=motion;Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(()=>motion.finish());
}
function openPlanSection(kind,status=undefined){
  return animateDetailNavigation(()=>{
    planSectionReturn={planId:selectedPlan,scrollTop:app.querySelector('.detail-scroll')?.scrollTop || 0,listScrollTop:app.querySelector('.plan-schedule-list')?.scrollTop || 0,filter:planStatusFilter,expanded:planSchedulesExpanded};
    planTopicsOverview=kind==='topics';planStatusOverview=kind==='status';selectedTopic=null;
    if(planStatusOverview)planSchedulesExpanded=true;
    if(status!==undefined)planStatusFilter=status==='all'?null:status;
    renderPlanDetail();app.querySelector('.detail-scroll').scrollTop=0;
  },false);
}
function closePlanSection(){
  return animateDetailNavigation(()=>{
    const previous=planSectionReturn;planSectionReturn=null;
    planTopicsOverview=false;planStatusOverview=false;selectedTopic=null;
    if(previous?.planId===selectedPlan){planStatusFilter=previous.filter;planSchedulesExpanded=previous.expanded;}
    renderPlanDetail();
    if(previous?.planId===selectedPlan){
      app.querySelector('.detail-scroll').scrollTop=previous.scrollTop;
      const list=app.querySelector('.plan-schedule-list');if(list)list.scrollTop=previous.listScrollTop;
    }
  },true);
}
function renderPlanStatusDetail(plan){
  const studies=planStatistics(plan.id).studies;
  app.innerHTML=`<article class="detail plan-detail plan-status-detail"><header class="toolbar">${iconButton('plan-section-back','계획 상세로 돌아가기','back')}<h2>전체 일정 상태별 개수</h2></header><div class="detail-scroll"><div class="detail-body"><div class="detail-heading">${renderPlanCertificateTarget(plan)}${plan.name!==planCertificate(plan).name?`<p class="overline">${escapeHTML(plan.name)}</p>`:''}</div>${renderPlanStatusOverview(studies,{detailsLink:false})}${renderPlanScheduleList(plan,studies)}</div></div></article>`;
}
function openPlanExport(){
  const plan=plans.find(item=>item.id===selectedPlan);if(!plan)return;
  document.querySelector('#export-plan-name').textContent=plan.name;
  document.querySelector('#export-calendar').value='';
  document.querySelector('#confirm-export-plan').disabled=true;
  showPhoneDialog('#export-plan');
}
document.querySelector('#export-calendar').addEventListener('change',event=>{
  document.querySelector('#confirm-export-plan').disabled=!event.target.value;
});
document.querySelector('#cancel-export-plan').addEventListener('click',()=>closePhoneDialog('#export-plan'));
document.querySelector('#export-plan-form').addEventListener('submit',async event=>{
  event.preventDefault();
  const calendar=document.querySelector('#export-calendar');if(!calendar.value)return;
  await closePhoneDialog('#export-plan');
  toast('내보내기 미리보기예요. 실제 캘린더에는 반영되지 않아요.');
});
let diagnosisRecordsReturn=null;
function openDiagnosisRecords(){
  if(!selectedPlan || diagnosisRecordsReturn)return;
  const saved={planId:selectedPlan,scrollTop:app.querySelector('.detail-scroll')?.scrollTop || 0,listScrollTop:app.querySelector('.plan-schedule-list')?.scrollTop || 0};
  animateDetailNavigation(()=>{diagnosisRecordsReturn=saved;renderPlanDetail();app.querySelector('.detail-scroll').scrollTop=0;},false,true,'records');
}
function closeDiagnosisRecords(){
  const saved=diagnosisRecordsReturn;if(!saved)return;
  animateDetailNavigation(()=>{diagnosisRecordsReturn=null;renderPlanDetail();app.querySelector('.detail-scroll').scrollTop=saved.scrollTop;app.querySelector('.plan-schedule-list').scrollTop=saved.listScrollTop;},true,true,'records');
}
function renderDiagnosisRecords(plan){
  const history=plan.diagnosisHistory || (plan.latestDiagnosis?[plan.latestDiagnosis]:[]);
  app.innerHTML=`<article class="detail plan-detail diagnosis-records-detail"><header class="toolbar">${iconButton('diagnosis-records-back','계획 상세로 돌아가기','back')}<h2>전체 진단 기록</h2></header><div class="detail-scroll"><div class="detail-body"><div class="detail-heading">${renderPlanCertificateTarget(plan)}<h1>${escapeHTML(plan.name)}</h1><p class="mobile-help">총 ${history.length}회 · 기록을 누르면 해당 회차의 결과를 볼 수 있어요.</p></div><div class="diagnosis-records-list">${history.length?history.map((record,index)=>({record,index})).reverse().map(({record,index})=>`<button type="button" class="card diagnosis-record-row" data-diagnosis-index="${index}"><span><strong>${index+1}차 자가 진단</strong><small>${escapeHTML(record.date || '날짜 기록 없음')}${record.demo?' · 예시 기록':''}</small></span><span><b>${record.total?Math.round(record.score/record.total*100):0}%</b><small>${record.score} / ${record.total}문항 정답</small></span><span aria-hidden="true">›</span></button>`).join(''):'<section class="card"><p class="empty">아직 자가 진단 기록이 없어요.</p></section>'}</div></div></div></article>`;
}
function renderDiagnosisHistory(plan){
  const all=plan.diagnosisHistory || (plan.latestDiagnosis?[plan.latestDiagnosis]:[]);
  const history=all.map((item,index)=>({item,index})).filter(({item})=>Number.isFinite(item.score) && item.total>0).slice(-7);
  const points=history.map(({item,index:historyIndex},index)=>({x:history.length===1?180:36+index*268/(history.length-1),y:108-Math.min(100,Math.max(0,item.score/item.total*100))*.8,percent:Math.round(item.score/item.total*100),attempt:historyIndex+1,historyIndex,item}));
  return `<section class="card diagnosis-history"><header><h2>최근 자가 진단 결과</h2><button type="button" data-action="diagnosis-records-details">자세히 보기</button></header><p class="diagnosis-history-caption">최근 7회 · 정답률</p><svg viewBox="0 0 340 148" role="group" aria-label="${points.length?'최근 진단 정답률: '+points.map(point=>point.attempt+'차 '+point.percent+'%').join(', '):'아직 자가 진단 기록이 없습니다'}">${[0,50,100].map(value=>`<line x1="36" x2="316" y1="${108-value*.8}" y2="${108-value*.8}" stroke="#e6ece8" stroke-dasharray="3 4"/><text x="24" y="${112-value*.8}" text-anchor="end" class="diagnosis-axis">${value}</text>`).join('')}${points.length>1?`<polyline points="${points.map(point=>point.x+','+point.y).join(' ')}" fill="none" stroke="#518967" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>`:''}${points.map(point=>`<g class="diagnosis-history-point" data-diagnosis-index="${point.historyIndex}" role="button" tabindex="0" aria-label="${point.attempt}차 진단 결과 ${point.percent}% 보기"><circle class="diagnosis-point-hit" cx="${point.x}" cy="${point.y}" r="13" fill="transparent"/><circle cx="${point.x}" cy="${point.y}" r="4" fill="#518967" stroke="white" stroke-width="2"><title>${point.attempt}차 · ${point.percent}% (${point.item.score}/${point.item.total})${point.item.date?' · '+escapeHTML(point.item.date):''}</title></circle><text x="${point.x}" y="${point.y-10}" text-anchor="middle" class="diagnosis-point-value">${point.percent}%</text><text x="${point.x}" y="132" text-anchor="middle" class="diagnosis-axis">${point.attempt}차</text></g>`).join('')}</svg>${points.length?`<p>최근 결과 <strong>${points.at(-1).percent}%</strong> · ${points.at(-1).item.score}/${points.at(-1).item.total}문항 정답</p>`:'<p>자가 진단을 완료하면 결과가 여기에 쌓여요.</p>'}</section>`;
}
function examCountdown(examDate) {
  const days=Math.round((Date.parse(examDate)-Date.parse(TODAY))/86400000);
  return days===0?'D-Day':days>0?`D-${days}`:`D+${Math.abs(days)}`;
}
function renderPlanDetail(){
  hideQuickOverview();
  const plan=plans.find(plan=>plan.id===selectedPlan);if(!plan){selectedPlan=null;renderCalendar();return;}
  if(diagnosisRecordsReturn?.planId===plan.id){renderDiagnosisRecords(plan);return;}
  if(selectedTopic!==null){renderPlanTopic(plan);return;}
  if(planTopicsOverview){renderPlanTopicsOverview(plan);return;}
  if(planStatusOverview){renderPlanStatusDetail(plan);return;}
  const stats=planStatistics(plan.id),overdue=stats.progress<stats.expectedProgress,canReplan=remainingReplanSources(plan.id).length>0 || overdue;
  const first=plan.startDate || stats.studies.map(e=>e.date).sort()[0];
  const countdown=examCountdown(plan.examDate);
  const dates=`<section class="card plan-key-dates" aria-label="학습 준비 기간"><div><span class="plan-date-label">준비 시작</span>${first?`<time datetime="${first}" aria-label="${longDate(first)}">${first.replaceAll('-','.')}</time>`:'<strong>미설정</strong>'}</div><div><div class="plan-date-label"><span class="plan-exam-label">시험일 <span class="plan-exam-countdown">${countdown}</span></span><button type="button" class="plan-exam-change" data-edit-exam="${plan.id}" aria-label="${escapeHTML(plan.name)} 시험일 수정">수정</button></div><time datetime="${plan.examDate}" aria-label="${longDate(plan.examDate)}">${plan.examDate.replaceAll('-','.')}</time></div></section>`;
  app.innerHTML=`<article class="detail plan-detail"><header class="toolbar">${iconButton('plan-back','달력으로 돌아가기','back')}<h2>학습 계획 상세</h2><div class="plan-management-actions">${iconButton('rename-plan','계획 이름 변경','edit')}${iconButton('delete-plan','계획 삭제','trash')}</div></header><div class="detail-scroll"><div class="detail-body"><div class="detail-heading">${renderPlanCertificateTarget(plan)}<h1>${escapeHTML(plan.name)}</h1><span class="plan-tag">${plan.active?'활성화된 계획':'비활성화된 계획'}</span></div>${dates}${renderDiagnosisHistory(plan)}<button type="button" class="replan-remaining-button${overdue?' active':''}" data-action="replan-remaining" ${canReplan?'':'disabled'}>${overdue?svg('alert'):''}남은 진행도로 일정 다시 짜기</button><button type="button" class="plan-export-button plan-diagnosis-button" data-action="diagnose-plan" ${plan.active?'':'disabled title="활성화된 계획에서 자가 진단을 할 수 있어요"'}>자가 진단</button><button type="button" class="plan-export-button" data-action="export-plan-calendar">${svg('calendar')}캘린더로 내보내기</button><section class="card plan-overview">${renderProgressComparison(stats)}<dl><div><dt>배정 시간</dt><dd>${planTimeLabel(stats.minutes)}</dd></div><div><dt>학습 반영 시간</dt><dd>${planTimeLabel(stats.completedMinutes)}</dd></div><div><dt>완료 일정</dt><dd>${stats.done} / ${stats.studies.length}개</dd></div></dl></section>${renderPlanStatusOverview(stats.studies)}${renderTopicAllocation(stats)}${renderPlanScheduleList(plan,stats.studies)}</div></div></article>`;
}
function renderPlanScheduleList(plan,studies){
  const filter=planStatusOverview?planStatusFilter:null;
  const filtered=studies.filter(e=>(!filter || (STATUS[e.status]?e.status:'unknown')===filter)).sort((a,b)=>a.date.localeCompare(b.date)||a.start.localeCompare(b.start)||a.id.localeCompare(b.id));
  return `<h2 class="section-heading plan-schedule-heading"><button type="button" data-action="toggle-plan-schedules" aria-expanded="${planSchedulesExpanded}" aria-controls="plan-schedules-panel"><span>${filter?STATUS[filter].label+' 일정':'전체 학습 일정'} <span class="topic-event-count">${filtered.length}개${filter?` / 전체 ${studies.length}개`:''}</span></span><span class="plan-schedule-chevron" aria-hidden="true">${planSchedulesExpanded?'⌃':'⌄'}</span></button></h2><div id="plan-schedules-panel" class="plan-schedules-panel${planSchedulesExpanded?' expanded':''}" ${planSchedulesExpanded?'':'inert aria-hidden="true"'}><div class="detail-schedule-content"><p class="plan-stats-note" role="status">${filter?'선택한 상태를 다시 누르면 전체 일정을 보여줘요.':(planStatusOverview?'원 그래프나 범례를 누르면 상태별 일정을 볼 수 있어요.':'전체 학습 일정이에요.')} 일정을 누르면 해당 주 보기로 이동해요.</p><section class="card plan-upcoming plan-schedule-list" aria-label="계획의 학습 일정 목록">${filtered.length?filtered.map(e=>{const state=STATUS[e.status] || STATUS.unknown;return `<button type="button" data-locate-event="${e.id}"><span><strong>${escapeHTML(e.title)}</strong><small>${longDate(e.date)} · ${e.start}–${e.end}</small><span class="plan-schedule-status" style="color:${state.color}">${state.label}${e.status==='partial'?` · ${progressForEvent(e)}%`:''}</span></span><span aria-hidden="true">›</span></button>`;}).join(''):`<p class="empty">${filter?'해당 상태의 일정이 없어요.':'배정된 학습 일정이 없어요.'}</p>`}</section></div></div>`;
}
function renderPlanStatusOverview(studies,{detailsLink=true}={}){
  const keys=['done','partial','missed','unknown'];
  const counts=Object.fromEntries(keys.map(key=>[key,studies.filter(e=>(STATUS[e.status]?e.status:'unknown')===key).length]));
  const label=keys.map(key=>`${STATUS[key].label} ${counts[key]}개`).join(', ');
  return `<section class="card topic-status-card plan-status-overview"><header class="allocation-heading"><h2>전체 일정 상태별 개수</h2>${detailsLink?'<button type="button" data-action="plan-status-details">자세히 보기</button>':''}</header><div class="topic-chart-layout">${renderTopicStatusChart(counts,studies.length,label)}<div class="topic-status-legend" role="group" aria-label="${planStatusOverview?'계획 전체 일정 상태 필터':'상태별 일정 자세히 보기'}">${keys.map(key=>`<button type="button" data-topic-status="${key}" aria-pressed="${planStatusOverview && planStatusFilter===key}"><span><i style="background:${STATUS[key].color}" aria-hidden="true"></i>${STATUS[key].label}</span><strong>${counts[key]}개</strong></button>`).join('')}</div></div></section>`;
}
function topicSchedules(planId,name){return events.filter(e=>e.type==='study' && e.planId===planId && (e.topic || e.title)===name).sort((a,b)=>a.date.localeCompare(b.date)||a.start.localeCompare(b.start)||a.id.localeCompare(b.id));}
function captureDetailList(){
  const selector=selectedTopic===null?'.plan-schedules-panel':'.topic-schedule-list',element=app.querySelector(selector);
  const height=element?.getBoundingClientRect?.().height || 0;
  return {selector,height,clone:height>0?element.cloneNode(true):null};
}
function animateDetailList(previous){
  for(const stop of [...detailListMotions])stop();
  const element=app.querySelector(previous.selector);
  if(!element?.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;
  const height=element.getBoundingClientRect?.().height || 0,overflow=element.style.overflow;
  element.style.overflow='hidden';
  const options={duration:280,easing:'cubic-bezier(.22,.75,.18,1)',fill:'both'};
  const animations=[element.animate([{height:`${previous.height}px`},{height:`${height}px`}],options)];
  const content=element.querySelector('.detail-schedule-content');
  if(content?.animate)animations.push(content.animate([{opacity:0,transform:'translateY(5px)'},{opacity:1,transform:'translateY(0)'}],options));
  const ghost=previous.clone;
  if(ghost && previous.height>0){
    ghost.removeAttribute('id');ghost.inert=true;ghost.setAttribute('aria-hidden','true');
    Object.assign(ghost.style,{position:'absolute',top:'0',left:'0',width:'100%',height:`${previous.height}px`,margin:'0',pointerEvents:'none',zIndex:'2'});
    element.append(ghost);
    if(ghost.animate)animations.push(ghost.animate([{opacity:1},{opacity:0}],options));
    else ghost.remove();
  }
  const stop=()=>{if(!detailListMotions.delete(stop))return;animations.forEach(animation=>animation.cancel());ghost?.remove();element.style.overflow=overflow;};
  detailListMotions.add(stop);
  Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(stop);
}
function togglePlanSchedules(){
  if(!selectedPlan || selectedTopic!==null)return;
  const previous=captureDetailList(),scroll=app.querySelector('.detail-scroll')?.scrollTop || 0;
  planSchedulesExpanded=!planSchedulesExpanded;renderPlanDetail();
  app.querySelector('.detail-scroll').scrollTop=scroll;animateDetailList(previous);
  app.querySelector('[data-action="toggle-plan-schedules"]')?.focus({preventScroll:true});
}
function toggleTopicStatus(status){
  if(status!=='all' && !Object.hasOwn(STATUS,status))return;
  if(selectedPlan && selectedTopic===null && !planStatusOverview && !planTopicsOverview){openPlanSection('status',status);return;}
  if(selectedTopic===null && !planStatusOverview)return;
  const scroll=app.querySelector('.detail-scroll')?.scrollTop || 0,previous=captureDetailList();
  if(selectedTopic===null){planSchedulesExpanded=true;}
  if(selectedTopic===null)planStatusFilter=status==='all' || planStatusFilter===status?null:status;
  else topicStatusFilter=status==='all' || topicStatusFilter===status?null:status;
  renderPlanDetail();app.querySelector('.detail-scroll').scrollTop=scroll;animateDetailList(previous);
  app.querySelector(`button[data-topic-status="${status}"]`)?.focus({preventScroll:true});
}
function renderTopicStatusChart(counts,total,label,interactive=true,filterOverride=undefined){
  const filter=filterOverride!==undefined?filterOverride:interactive?(selectedTopic===null?(planStatusOverview?planStatusFilter:null):topicStatusFilter):null;
  let cursor=0;
  const point=(radius,percent)=>{const angle=(percent/100*360-90)*Math.PI/180;return `${71+radius*Math.cos(angle)} ${71+radius*Math.sin(angle)}`;};
  const slices=['done','partial','missed','unknown'].map(status=>{
    if(!counts[status] || !total)return '';
    const from=cursor;cursor+=counts[status]/total*100;
    const large=cursor-from>50?1:0;
    const path=counts[status]===total?'M71 6 A65 65 0 1 1 71 136 A65 65 0 1 1 71 6 Z M71 28 A43 43 0 1 1 71 114 A43 43 0 1 1 71 28 Z':`M${point(65,from)} A65 65 0 ${large} 1 ${point(65,cursor)} L${point(43,cursor)} A43 43 0 ${large} 0 ${point(43,from)} Z`;
    return `<path d="${path}" fill="${STATUS[status].color}" fill-rule="evenodd" class="topic-chart-slice${filter===status?' selected':''}${filter && filter!==status?' muted':''}" ${interactive?`data-topic-status="${status}" role="button" tabindex="0" aria-pressed="${filter===status}" aria-label="${STATUS[status].label} ${counts[status]}개 일정 보기"`:'aria-hidden="true"'}><title>${STATUS[status].label} ${counts[status]}개</title></path>`;
  }).join('');
  return `<div class="topic-status-chart${interactive?'':' chart-static'}"><svg viewBox="0 0 142 142" role="${interactive?'group':'img'}" aria-label="${label}">${total?slices:'<circle cx="71" cy="71" r="54" fill="none" stroke="#e8ebe9" stroke-width="22"/>'}</svg><${interactive?'button type="button"':'span'} class="topic-chart-total" ${interactive?`data-topic-status="all" aria-pressed="${filter===null}" aria-label="전체 ${total}개 일정 보기"`:'aria-hidden="true"'}><strong>${total}</strong><small>전체 일정</small></${interactive?'button':'span'}></div>`;
}
function openPlanTopic(name){
  return animateDetailNavigation(()=>{
    topicDetailReturn={planId:selectedPlan,overview:planTopicsOverview,scrollTop:app.querySelector('.detail-scroll')?.scrollTop || 0,listScrollTop:app.querySelector('.plan-schedule-list')?.scrollTop || 0,filter:planStatusFilter,expanded:planSchedulesExpanded};
    selectedTopic=name;topicStatusFilter=null;renderPlanDetail();
    app.querySelector('.detail-scroll').scrollTop=0;
  },false);
}
function returnFromPlanTopic(){
  return animateDetailNavigation(()=>{
    const previous=topicDetailReturn;topicDetailReturn=null;
    selectedTopic=null;topicStatusFilter=null;
    if(previous?.planId===selectedPlan){planTopicsOverview=previous.overview;planStatusFilter=previous.filter;planSchedulesExpanded=previous.expanded;}
    renderPlanDetail();
    if(previous?.planId===selectedPlan){
      app.querySelector('.detail-scroll').scrollTop=previous.scrollTop;
      const list=app.querySelector('.plan-schedule-list');if(list)list.scrollTop=previous.listScrollTop;
    }
  },true);
}
function renderPlanTopic(plan){
  const studies=topicSchedules(plan.id,selectedTopic),keys=['done','partial','missed','unknown'];
  const counts=Object.fromEntries(keys.map(key=>[key,studies.filter(e=>(STATUS[e.status]?e.status:'unknown')===key).length]));
  const stats=planStatistics(plan.id).topics.find(topic=>topic.name===selectedTopic) || {progress:0,expectedProgress:0};
  const filtered=topicStatusFilter?studies.filter(e=>(STATUS[e.status]?e.status:'unknown')===topicStatusFilter):studies;
  const chartLabel=keys.map(key=>`${STATUS[key].label} ${counts[key]}개`).join(', ');
  app.innerHTML=`<article class="detail plan-detail plan-topic-detail"><header class="toolbar">${iconButton('topic-back',planTopicsOverview?'학습 주제별 시간 배정으로 돌아가기':'계획 상세로 돌아가기','back')}<h2>학습 주제 상세</h2></header><div class="detail-scroll"><div class="detail-body"><div class="detail-heading">${renderPlanCertificateTarget(plan)}${plan.name!==planCertificate(plan).name?`<p class="overline">${escapeHTML(plan.name)}</p>`:''}<h1>${escapeHTML(selectedTopic)}</h1></div><section class="card plan-overview topic-progress-overview" aria-label="학습 주제 진행률 비교">${renderProgressComparison(stats,'학습 주제 진행률')}</section><section class="card topic-status-card"><h2>일정 상태별 개수</h2><div class="topic-chart-layout">${renderTopicStatusChart(counts,studies.length,chartLabel)}<div class="topic-status-legend" role="group" aria-label="일정 상태 필터">${keys.map(key=>`<button type="button" data-topic-status="${key}" aria-pressed="${topicStatusFilter===key}"><span><i style="background:${STATUS[key].color}" aria-hidden="true"></i>${STATUS[key].label}</span><strong>${counts[key]}개</strong></button>`).join('')}</div></div></section><h2 class="section-heading">${topicStatusFilter?STATUS[topicStatusFilter].label+' 일정':'배정된 일정'} <span class="topic-event-count">${filtered.length}개${topicStatusFilter?` / 전체 ${studies.length}개`:''}</span></h2><p class="plan-stats-note" role="status">${topicStatusFilter?'선택한 상태를 다시 누르면 전체 일정을 보여줘요. ':'원 그래프나 범례를 누르면 상태별로 볼 수 있어요. '}일정을 누르면 해당 주의 달력으로 이동해 잠시 강조해요.${plan.active?'':' 비활성 계획은 선택한 일정만 잠시 표시해요.'}</p><section class="card topic-schedule-list"><div class="detail-schedule-content">${filtered.length?filtered.map(e=>{const state=STATUS[e.status] || STATUS.unknown;return `<button type="button" data-locate-event="${e.id}"><span class="topic-schedule-dot" style="background:${state.color}" aria-hidden="true"></span><span><strong>${escapeHTML(e.title)}</strong><small>${longDate(e.date)}<br>${e.start}–${e.end} · ${duration(e)}</small><span class="topic-schedule-status" style="color:${state.color}">${state.label}${e.status==='partial'?` · ${progressForEvent(e)}%`:''}</span></span><span class="topic-schedule-arrow" aria-hidden="true">›</span></button>`;}).join(''):`<p class="empty">${topicStatusFilter?'해당 상태의 일정이 없어요.':'배정된 일정이 없어요.'}</p>`}</div></section></div></div></article>`;
}
function finishCalendarSpotlight(id){
  if(calendarSpotlight!==id)return;
  calendarSpotlight=null;calendarSpotlightTimer=null;
  app.querySelector(`[data-event="${id}"]`)?.classList.remove('calendar-spotlight');
  const event=events.find(e=>e.id===id);
  if(event && !isEventVisible(event) && !selectedPlan && !selectedEvent && !planCreation && !pointerMove && !pendingMove){
    const scroll=app.querySelector('.time-scroll')?.scrollTop || 0;renderCalendar();
    const scroller=app.querySelector('.time-scroll');if(scroller)scroller.scrollTop=scroll;
  }
}
function locatePlanEvent(id){
  const event=events.find(e=>e.id===id && e.type==='study' && e.planId===selectedPlan);if(!event)return;
  topicCalendarReturn={planId:selectedPlan,topic:selectedTopic,statusFilter:topicStatusFilter,planFilter:planStatusFilter,topicsOverview:planTopicsOverview,statusOverview:planStatusOverview,expanded:planSchedulesExpanded,listScrollTop:app.querySelector('.plan-schedule-list')?.scrollTop || 0,scrollTop:app.querySelector('.detail-scroll')?.scrollTop || 0,planReturnView:planDetailReturnView,eventId:id};
  locateCalendarSchedule(event,true);
}
function locateCalendarSchedule(event,keepTopicReturn=false,keepConflictReturn=false){
  if(!keepConflictReturn)conflictCalendarReturn=null;
  const id=event.id;
  if(!keepTopicReturn)topicCalendarReturn=null;
  clearTimeout(calendarSpotlightTimer);calendarSpotlight=id;
  selectedPlan=null;selectedTopic=null;planDetailReturnView=null;selectedEvent=null;searchOpen=false;
  calendarView='week';viewDate=parseDate(event.date);selectedDate=event.date;renderCalendar();
  const scroller=app.querySelector('.time-scroll');if(scroller)scroller.scrollTop=Math.max(0,(minutesOfDay(event.start)-90)*timelineScale);
  app.querySelector(`[data-event="${id}"]`)?.focus({preventScroll:true});
  calendarSpotlightTimer=setTimeout(()=>finishCalendarSpotlight(id),4000);
}
function calendarReturnNotice(){
  if(!topicCalendarReturn && !conflictCalendarReturn)return '';
  const conflict=Boolean(conflictCalendarReturn);
  return `<aside id="calendar-return-notice" class="calendar-return-notice" aria-label="원래 목록으로 돌아가기"><button type="button" data-action="${conflict?'return-conflicts':'return-topic'}">${svg('back')}${conflict?'충돌 일정 목록으로 돌아가기':topicCalendarReturn.topic===null?'학습 계획 상세로 돌아가기':'학습 주제 상세로 돌아가기'}</button></aside>`;
}
function clearCalendarReturns(){
  topicCalendarReturn=null;conflictCalendarReturn=null;
  document.querySelector('.phone').classList.remove('has-calendar-return');
  const notice=app.querySelector('#calendar-return-notice');
  if(!notice || notice.classList.contains('calendar-return-closing'))return;
  if(!window.matchMedia('(prefers-reduced-motion: reduce)').matches && notice.animate){
    const current=getComputedStyle(notice);
    const from={opacity:current.opacity,transform:current.transform};
    notice.classList.add('calendar-return-closing');notice.inert=true;
    notice.setAttribute('aria-hidden','true');notice.removeAttribute('id');
    document.querySelector('.phone').append(notice);
    notice.style.animation='none';
    const animation=notice.animate([from,{opacity:0,transform:'translateY(20px) scale(.985)'}],{duration:240,easing:'cubic-bezier(.4,0,.7,.3)',fill:'forwards'});
    animation.finished.catch(()=>{}).then(()=>{notice.remove();animation.cancel();});
  }else notice.remove();
}
function dismissCalendarReturnOnOutside(event){
  if(phoneDialogBackgroundLock)return;
  if(fabCreateMenuOpen && event.type!=='pointerdown' && !event.target.closest?.('#fab-create-actions') && !event.target.closest?.('[data-action="add"]') && !event.target.closest?.('[data-action="quick-overview"]') && !event.target.closest?.('[data-action="self-diagnosis"]') && !event.target.closest?.('[data-action="info"],[data-edit-exam]')){
    setFabCreateMenu(false);event.preventDefault?.();event.stopPropagation?.();return;
  }
  dismissReplanHighlight(event);
  if((topicCalendarReturn || conflictCalendarReturn) && !event.target.closest?.('#calendar-return-notice'))clearCalendarReturns();
}
document.addEventListener('pointerdown',dismissCalendarReturnOnOutside,{capture:true});
document.addEventListener('click',dismissCalendarReturnOnOutside,{capture:true});
function returnToConflictList(){
  const previous=conflictCalendarReturn;if(!previous || pendingMove || pointerMove || moveClosing)return;
  clearCalendarReturns();clearTimeout(calendarSpotlightTimer);calendarSpotlight=null;calendarSpotlightTimer=null;
  selectedPlan=null;selectedTopic=null;selectedEvent=null;searchOpen=false;
  calendarView=previous.calendarView;viewDate=previous.viewDate;selectedDate=previous.selectedDate;renderCalendar();showCalendarConflicts(true);
  document.querySelector('#calendar-conflicts').scrollTop=previous.scrollTop;
  document.querySelector('#calendar-conflict-list').scrollTop=previous.listScrollTop;
  document.querySelector(`[data-conflict-event="${previous.eventId}"]`)?.focus({preventScroll:true});
}
function returnToPlanTopic(){
  const previous=topicCalendarReturn;if(!previous || pendingMove || pointerMove || moveClosing)return;
  clearCalendarReturns();clearTimeout(calendarSpotlightTimer);calendarSpotlight=null;calendarSpotlightTimer=null;
  selectedPlan=previous.planId;selectedTopic=previous.topic;planTopicsOverview=previous.topicsOverview ?? false;planStatusOverview=previous.statusOverview ?? false;topicStatusFilter=previous.statusFilter ?? null;planStatusFilter=previous.planFilter ?? null;planSchedulesExpanded=previous.expanded ?? false;planDetailReturnView=previous.planReturnView;
  selectedEvent=null;searchOpen=false;renderCalendar();
  const scroller=app.querySelector('.detail-scroll');if(scroller)scroller.scrollTop=previous.scrollTop;
  if(previous.topic===null){const list=app.querySelector('.plan-schedule-list');if(list)list.scrollTop=previous.listScrollTop || 0;}
  app.querySelector(`[data-locate-event="${previous.eventId}"]`)?.focus({preventScroll:true});
}
function planCertificate(plan){
  const id=plan.certificateId || (['engineer','sqld','computer'].includes(plan.id)?plan.id:null);
  return {
    engineer:{id:'engineer',name:'정보처리기사 필기',short:'기사',icon:'code',color:'#39784c',background:'#e8f2eb'},
    sqld:{id:'sqld',name:'SQL 개발자(SQLD)',short:'SQLD',icon:'database',color:'#5572ad',background:'#edf1f9'},
    computer:{id:'computer',name:'컴퓨터활용능력 1급 필기',short:'컴활',icon:'monitor',color:'#8a6aaa',background:'#f2edf8'}
  }[id] || {id:'other',name:'자격증',short:'자격',icon:'award',color:'#7c8580',background:'#eff2f0'};
}
function renderPlanCertificateTarget(plan){
  const certificate=planCertificate(plan || {});
  return `<div class="plan-target-certificate">${renderPlanCertificateBadge(certificate)}<span>${escapeHTML(certificate.name)}</span></div>`;
}
function renderPlanCertificateBadge(certificate){
  return `<span class="plan-certificate-badge" data-certificate="${certificate.id}" role="img" aria-label="목표 자격증: ${escapeHTML(certificate.name)}" title="${escapeHTML(certificate.name)}" style="--certificate-color:${certificate.color};--certificate-bg:${certificate.background}">${svg(certificate.icon)}<small>${certificate.short}</small></span>`;
}
function renderPlanList() {
  syncPlanActivity();
  document.querySelector('#drawer-conflicts').innerHTML=conflictIndicator();
  document.querySelector('#plans-list').innerHTML=plans.map(plan=>{
    const stats=planStatistics(plan.id),certificate=planCertificate(plan);
    return `<article data-plan-detail="${plan.id}" class="plan-row ${plan.active?'':'plan-row-inactive'}">${stats.progress<stats.expectedProgress?'<span class="plan-overdue-dot" role="img" aria-label="예상 학습 진행률보다 뒤처짐" title="예상 학습 진행률보다 뒤처져 있어요"></span>':''}<div class="plan-row-info"><h3><button type="button" class="plan-detail-link" data-open-plan="${plan.id}" aria-label="${escapeHTML(plan.name)}, 목표 자격증 ${escapeHTML(certificate.name)}, 자세히 보기"><span class="plan-name-label">${renderPlanCertificateBadge(certificate)}<span class="plan-name-text">${escapeHTML(plan.name)}</span></span><span aria-hidden="true">›</span></button></h3><div class="plan-overview sidebar-plan-progress ${stats.progress<stats.expectedProgress?'behind':stats.progress>stats.expectedProgress?'ahead':'on-track'}">${renderProgressTrack(stats,plan.name+' 실제 학습 진행률')}</div><div class="sidebar-plan-footer"><p>시험일 <time datetime="${plan.examDate}">${plan.examDate.replaceAll('-','.')}</time> <span class="plan-exam-countdown">${examCountdown(plan.examDate)}</span></p><button type="button" class="plan-exam-edit" data-edit-exam="${plan.id}" aria-label="${escapeHTML(plan.name)} 시험일 변경">시험일 변경</button></div></div><button type="button" class="plan-switch" role="switch" aria-checked="${plan.active}" aria-label="${escapeHTML(plan.name)} 활성화" data-plan-toggle="${plan.id}"><span></span></button></article>`;
  }).join('');
}
function renderViewSelector() {
  document.querySelector('#calendar-view-options').innerHTML = Object.entries(VIEW_LABELS).map(([key,label])=>`<button type="button" data-calendar-view="${key}" class="calendar-view-choice" aria-pressed="${calendarView===key}"><span class="view-choice-icon view-choice-${key}" aria-hidden="true"></span>${label}<span class="view-choice-check" aria-hidden="true">${calendarView===key?'✓':''}</span></button>`).join('');
}
function setCalendarView(mode) {
  if (!Object.hasOwn(VIEW_LABELS,mode)) return;
  // Keep the selected day when it belongs to the displayed month.
  const selected=parseDate(selectedDate);
  if(selected.getFullYear()===viewDate.getFullYear()&&selected.getMonth()===viewDate.getMonth())viewDate=selected;
  selectedDate=dateKey(viewDate);calendarView=mode;selectedEvent=null;searchOpen=false;
  renderCalendar();renderViewSelector();
  if(plansDrawerOpen)closePlansDrawer();
}
document.querySelector('#calendar-view-options').addEventListener('click', action=>{
  const button=action.target.closest('[data-calendar-view]');
  if(button)setCalendarView(button.dataset.calendarView);
});
let activePlansOverview=null;
let selfDiagnosis=null,diagnosisCompletionOrder=0;
function openSelfDiagnosis(planId=null){
  syncPlanActivity();
  if(selfDiagnosis || planCreation || selectedEvent)return;
  const plan=planId?plans.find(item=>item.id===planId && item.active):null;
  if(planId && !plan)return;
  const certificate=plan && PLAN_CERTIFICATES.find(item=>item.id===planCertificate(plan).id);
  const origin={...captureFabCreationReturn(),menuOpen:fabCreateMenuOpen,selectedPlan};
  if(selectedPlan)origin.scrolls.push(...['.detail-scroll','.plan-schedule-list'].map(selector=>({selector,top:app.querySelector(selector)?.scrollTop || 0})));
  animateDetailNavigation(()=>{selfDiagnosis={origin,planId:plan?.id || null,answers:certificate?Array(certificate.questions.length).fill(null):[],result:null};renderSelfDiagnosis();},false,true,origin.selectedPlan?'diagnosis':'fade');
}
function openDiagnosisHistory(index){
  const plan=plans.find(item=>item.id===selectedPlan);
  const all=plan?.diagnosisHistory || (plan?.latestDiagnosis?[plan.latestDiagnosis]:[]),assessment=all[index];
  if(!plan || !Number.isInteger(index) || !assessment)return;
  const origin={...captureFabCreationReturn(),menuOpen:false,selectedPlan,fromDiagnosisRecords:Boolean(diagnosisRecordsReturn)};
  origin.scrolls.push(...['.detail-scroll','.plan-schedule-list'].map(selector=>({selector,top:app.querySelector(selector)?.scrollTop || 0})));
  animateDetailNavigation(()=>{
    selfDiagnosis={origin,planId:plan.id,answers:[...(assessment.answers || [])],result:{score:assessment.score},historyAttempt:index+1,historyDate:assessment.date,example:Boolean(assessment.demo)};
    renderSelfDiagnosis();
  },false,true,'history');
}
function closeSelfDiagnosis(){
  const saved=selfDiagnosis?.origin;if(!saved)return;
  const motionStyle=selfDiagnosis.historyAttempt?'history':saved.selectedPlan?'diagnosis':'fade';
  animateDetailNavigation(()=>{
    selfDiagnosis=null;
    ({calendarView,selectedDate,viewDate,monthAgendaExpanded,quickOverviewOpen,quickStatusFilter}=saved);
    selectedPlan=saved.selectedPlan || null;
    renderCalendar();saved.scrolls.forEach(({selector,top})=>{const node=app.querySelector(selector);if(node)node.scrollTop=top;});
    setFabCreateMenu(saved.menuOpen,true);
  },true,true,motionStyle);
}
function renderSelfDiagnosis(){
  hideQuickOverview();
  const draft=selfDiagnosis,plan=plans.find(item=>item.id===draft.planId && (item.active || draft.historyAttempt));
  const certificate=plan && PLAN_CERTIFICATES.find(item=>item.id===planCertificate(plan).id);
  let content='',footer='';
  if(!draft.planId){
    const active=plans.filter(item=>item.active).sort((a,b)=>Number(hasTodayDiagnosis(a))-Number(hasTodayDiagnosis(b)) || (hasTodayDiagnosis(a)&&hasTodayDiagnosis(b)?todayDiagnosisOrder(a)-todayDiagnosisOrder(b):0));
    content=`<h2>어떤 계획을 진단할까요?</h2><p class="creation-description">활성화된 학습 계획을 선택해주세요.</p><div class="certificate-list">${active.length?active.map(item=>`<button type="button" class="certificate-option ${hasTodayDiagnosis(item)?'diagnosis-complete':'diagnosis-pending'}" data-diagnose-plan="${escapeHTML(item.id)}">${renderPlanCertificateBadge(planCertificate(item))}<span><strong>${escapeHTML(item.name)}</strong><span>${escapeHTML(planCertificate(item).name)}</span><small class="diagnosis-today-badge ${hasTodayDiagnosis(item)?'complete':'pending'}">${hasTodayDiagnosis(item)?'오늘 진단 완료':'오늘 진단 안 함'}</small><small class="diagnosis-recency">${escapeHTML(diagnosisRecencyLabel(item))}</small></span><i aria-hidden="true">›</i></button>`).join(''):'<p class="empty">활성화된 학습 계획이 없어요. 사이드바에서 계획을 활성화해주세요.</p>'}</div>`;
  }else if(!plan || !certificate){
    content='<p class="empty">이 계획의 진단 평가를 사용할 수 없어요.</p>';
  }else if(draft.result){
    const weakTopics=certificate.topics.filter((topic,index)=>draft.answers[index]!==certificate.questions[index].answer);
    content=`${renderPlanCertificateTarget(plan)}<h2>${draft.historyAttempt?draft.historyAttempt+'차 진단 결과':'진단 결과'}</h2>${draft.historyAttempt?`<p class="creation-description">${escapeHTML(draft.historyDate || '')}${draft.example?' · 예시 기록':''}</p>`:''}<p class="creation-description">${escapeHTML(plan.name)}</p><div class="diagnosis-summary"><span>정답 수</span><strong>${draft.result.score} / ${certificate.questions.length}</strong><p>진단 결과는 학습 일정의 완료 기록과 별도로 저장돼요.</p></div>${certificate.questions.map((question,index)=>`<section class="creation-section"><h3>${escapeHTML(certificate.topics[index])} · ${draft.answers[index]===question.answer?'정답':'보완이 필요해요'}</h3><p>${escapeHTML(question.text)}</p><p>선택한 답: ${escapeHTML([...question.choices,'잘 모르겠어요'][draft.answers[index]] || '답변 기록 없음')}</p><p>정답: ${escapeHTML(question.choices[question.answer])}</p></section>`).join('')}`;
    content+=weakTopics.length?`<section class="creation-section"><h3>부족한 주제에 맞춰 다시 짤까요?</h3><p>${weakTopics.map(escapeHTML).join(' · ')}</p><p>해당 주제의 남은 학습을 우선 배치해요. 선호 시간과 시험일을 조절하고 변경 예정 일정을 확인한 뒤 반영할 수 있어요.</p>${remainingReplanSources(plan.id).length?'':'<p>현재 다시 배정할 남은 학습이 없어요.</p>'}</section>`:'<p class="creation-description">모든 주제를 잘 이해하고 있어요. 현재 학습 계획을 이어가세요.</p>';
    footer=`${weakTopics.length?`<button type="button" class="submit" data-diagnosis-action="replan" ${plan.active && remainingReplanSources(plan.id).length?'':'disabled'}>남은 일정 다시 짜기</button>`:''}${!draft.origin.selectedPlan?'<button type="button" class="diagnosis-keep-plan" data-diagnosis-action="more">다른 계획 진단하기</button>':''}<button type="button" class="diagnosis-keep-plan" data-diagnosis-action="back">${weakTopics.length?'현재 일정 유지하고 돌아가기':draft.origin.selectedPlan?'계획 상세로 돌아가기':'달력으로 돌아가기'}</button>`;
  }else{
    content=`${renderPlanCertificateTarget(plan)}<h2>지금 실력을 확인해볼까요?</h2><p class="creation-description">${escapeHTML(plan.name)} · 예시 진단 ${certificate.questions.length}문항</p>${certificate.questions.map((question,index)=>`<fieldset class="diagnosis-question"><legend><span>0${index+1}</span> ${escapeHTML(question.text)}</legend>${[...question.choices,'잘 모르겠어요'].map((choice,option)=>`<label class="diagnosis-answer"><input type="radio" name="self-diagnosis-${index}" data-self-question="${index}" value="${option}" ${draft.answers[index]===option?'checked':''}>${escapeHTML(choice)}</label>`).join('')}</fieldset>`).join('')}`;
    footer='<button type="button" class="submit" id="submit-self-diagnosis" data-diagnosis-action="submit" disabled>진단 결과 보기</button>';
  }
  app.innerHTML=`<section class="plan-creation self-diagnosis"><header class="toolbar"><button type="button" class="icon-btn" data-diagnosis-action="back" aria-label="${draft.origin.fromDiagnosisRecords?'전체 진단 기록으로 돌아가기':draft.origin.selectedPlan?'계획 상세로 돌아가기':'원래 달력으로 돌아가기'}">${svg('back')}</button><h1>자가 진단</h1></header><div class="creation-scroll">${content}</div>${footer?`<footer>${footer}</footer>`:''}</section>`;
}
function handleSelfDiagnosisClick(event){
  const button=event.target.closest('button');if(!button)return;
  if(button.dataset.diagnosisAction==='back'){closeSelfDiagnosis();return;}
  if(button.dataset.diagnosisAction==='more'){showDiagnosisSelection();return;}
  if(button.dataset.diagnosisAction==='replan'){startDiagnosisReplan();return;}
  if(button.dataset.diagnosePlan){
    const plan=plans.find(item=>item.id===button.dataset.diagnosePlan && item.active);if(!plan)return;
    const certificate=PLAN_CERTIFICATES.find(item=>item.id===planCertificate(plan).id);
    const scroll=app.querySelector('.creation-scroll');
    selfDiagnosis.selectionScroll=scroll?.scrollTop || 0;
    const top=scroll?.getBoundingClientRect?.().top || 0;
    selfDiagnosis.selectionPositions=[...app.querySelectorAll('[data-diagnose-plan]')].map(card=>({id:card.dataset.diagnosePlan,top:(card.getBoundingClientRect?.().top || 0)-top+(scroll?.scrollTop || 0)}));
    animateDetailNavigation(()=>{
      selfDiagnosis.planId=plan.id;selfDiagnosis.answers=Array(certificate?.questions.length || 0).fill(null);
      renderSelfDiagnosis();app.querySelector('.creation-scroll').scrollTop=0;
    },false,true,'diagnosis-list');return;
  }
  if(button.dataset.diagnosisAction==='submit'){
    if(selfDiagnosis.result)return;
    const plan=plans.find(item=>item.id===selfDiagnosis.planId && item.active),certificate=plan && PLAN_CERTIFICATES.find(item=>item.id===planCertificate(plan).id);
    if(!certificate || selfDiagnosis.answers.length!==certificate.questions.length || selfDiagnosis.answers.some(answer=>answer===null))return;
    const score=certificate.questions.filter((question,index)=>question.answer===selfDiagnosis.answers[index]).length;
    const history=plan.diagnosisHistory || (plan.latestDiagnosis?[plan.latestDiagnosis]:[]);
    const assessment={score,total:certificate.questions.length,answers:[...selfDiagnosis.answers],date:TODAY,completedOrder:++diagnosisCompletionOrder};
    plan.diagnosisHistory=[...history,assessment];plan.latestDiagnosis=assessment;
    selfDiagnosis.result={score};animateDetailNavigation(()=>renderSelfDiagnosis(),false,true,'fade');
  }
}
function showDiagnosisSelection(){
  if(!selfDiagnosis?.result)return;
  const completedId=selfDiagnosis.planId,positions=selfDiagnosis.selectionPositions || [],scrollTop=selfDiagnosis.selectionScroll || 0;
  animateDetailNavigation(()=>{
    selfDiagnosis.planId=null;selfDiagnosis.answers=[];selfDiagnosis.result=null;
    renderSelfDiagnosis();
    app.querySelector('.creation-scroll').scrollTop=scrollTop;
    app.querySelector(`[data-diagnose-plan="${completedId}"]`)?.focus({preventScroll:true});
  },true,true,'diagnosis-list');
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;
  const scroll=app.querySelector('.creation-scroll'),top=scroll?.getBoundingClientRect?.().top || 0;
  app.querySelectorAll('[data-diagnose-plan]').forEach(card=>{
    const previous=positions.find(position=>position.id===card.dataset.diagnosePlan);
    if(!previous || !card.animate || !card.getBoundingClientRect)return;
    const delta=previous.top-(card.getBoundingClientRect().top-top+(scroll?.scrollTop || 0));
    if(Math.abs(delta)<1 && card.dataset.diagnosePlan!==completedId)return;
    const start={transform:`translateY(${delta}px)`},end={transform:'translateY(0)'};
    if(card.dataset.diagnosePlan===completedId){
      const style=getComputedStyle(card);
      Object.assign(start,{backgroundColor:'#fff0ed',borderColor:'#e6a19a',boxShadow:'0 0 0 3px #dc796019'});
      Object.assign(end,{backgroundColor:style.backgroundColor || '#fff',borderColor:style.borderColor || '#e8e8eb',boxShadow:style.boxShadow || 'none'});
    }
    const animation=card.animate([start,end],{duration:360,delay:60,easing:'cubic-bezier(.22,.75,.18,1)',fill:'both'});
    const stop=()=>{animation.cancel();detailListMotions.delete(stop);};detailListMotions.add(stop);
    animation.finished.catch(()=>{}).then(stop);
  });
}
function startDiagnosisReplan(){
  const diagnosis=selfDiagnosis,plan=plans.find(item=>item.id===diagnosis?.planId && item.active);
  const certificate=plan && PLAN_CERTIFICATES.find(item=>item.id===planCertificate(plan).id);
  if(!diagnosis?.result || !certificate || !remainingReplanSources(plan.id).length)return;
  const priorityTopics=certificate.topics.filter((topic,index)=>diagnosis.answers[index]!==certificate.questions[index].answer);
  if(!priorityTopics.length)return;
  const scroll=app.querySelector('.creation-scroll')?.scrollTop || 0;
  animateDetailNavigation(()=>{
    selfDiagnosis=null;openRemainingReplan(plan.id);
    if(!planCreation){selfDiagnosis=diagnosis;renderSelfDiagnosis();return;}
    planCreation.priorityTopics=priorityTopics;
    planCreation.returnUi.diagnosis={state:diagnosis,scroll};
    renderPlanCreation();app.querySelector('.creation-scroll').scrollTop=0;
  },false,true,'diagnosis');
}
let fabCreateMenuOpen=false;
let fabScheduleReturn=null;
function captureFabCreationReturn(){
  return {calendarView,selectedDate,viewDate:new Date(viewDate),monthAgendaExpanded,quickOverviewOpen,quickStatusFilter,scrolls:['.time-scroll','.agenda-list','.year-view'].map(selector=>({selector,top:app.querySelector(selector)?.scrollTop || 0}))};
}
function restoreFabCreationReturn(saved,render=true){
  detailNavigationMotion?.finish();
  const phone=document.querySelector('.phone');phone.classList.add('fab-menu-restoring');
  ({calendarView,selectedDate,viewDate,monthAgendaExpanded,quickOverviewOpen,quickStatusFilter}=saved);
  if(render)renderCalendar();
  saved.scrolls.forEach(({selector,top})=>{const node=app.querySelector(selector);if(node)node.scrollTop=top;});
  setFabCreateMenu(true);
  // Commit the expanded state with transitions disabled before restoring interaction.
  void app.querySelector('.fab')?.offsetWidth;
  void document.querySelector('#fab-create-actions').offsetWidth;
  phone.classList.remove('fab-menu-restoring');
  app.querySelector('.fab')?.focus({preventScroll:true});
}
function setFabCreateMenu(open,instant=false){
  const phone=document.querySelector('.phone');
  if(instant)phone.classList.add('fab-menu-restoring');
  fabCreateMenuOpen=open;
  document.querySelector('.phone').classList.toggle('fab-create-open',open);
  const menu=document.querySelector('#fab-create-actions');menu.inert=!open || Boolean(phoneDialogBackgroundLock);menu.setAttribute('aria-hidden',String(!open));
  const button=app.querySelector('.fab');button?.setAttribute('aria-expanded',String(open));button?.setAttribute('aria-controls','fab-create-actions');button?.setAttribute('aria-label',open?'생성 메뉴 닫기':'계획 또는 일정 생성');
  if(instant){void button?.offsetWidth;void menu.offsetWidth;phone.classList.remove('fab-menu-restoring');}
}
let fabCreationEntering=false;
async function enterFabCreation(kind){
  if(fabCreationEntering)return;
  fabCreationEntering=true;
  try{
    const saved=captureFabCreationReturn();
    if(kind==='plan')setFabCreateMenu(false,true);
    if(kind==='plan')await openPlanCreation('fab');
    else {fabScheduleReturn=saved;openAddSchedule();if(!document.querySelector('#add').open){fabScheduleReturn=null;setFabCreateMenu(true);}}
  }finally{fabCreationEntering=false;}
}
document.querySelector('#fab-create-plan').addEventListener('click',()=>enterFabCreation('plan'));
document.querySelector('#fab-create-schedule').addEventListener('click',()=>enterFabCreation('schedule'));
let quickOverviewOpen=false,quickOverviewDrag=null,quickStatusFilter=null,quickClickUntil=0;
let agendaModeMotion=null;
function quickOverviewButton(){
  if(planCreation || planCalendarPreview || normalConflictDay)return '';
  const diagnosisClass=plans.some(plan=>plan.active && !hasTodayDiagnosis(plan))?' diagnosis-needed':'';
  return `<button type="button" class="quick-overview-button icon-btn" data-action="quick-overview" aria-label="전체 학습 현황 그래프" aria-controls="quick-overview-sheet" aria-expanded="${quickOverviewOpen}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="4"/><path d="M7 16v-4m5 4V7m5 9v-6"/></svg></button><button type="button" class="self-diagnosis-button icon-btn${diagnosisClass}" data-action="self-diagnosis" aria-label="자가 진단" title="자가 진단"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="4" width="14" height="17" rx="3"/><rect x="9" y="2" width="6" height="4" rx="1.5" fill="currentColor" stroke="none"/><path d="m8 13 2.5 2.5L16 10"/></svg></button>`;
}
function hideQuickOverview(){setFabCreateMenu(false);agendaModeMotion?.finish(false);quickOverviewOpen=false;syncQuickOverview();}
function mountQuickOverview(){
  const sheet=document.querySelector('#quick-overview-sheet'),agenda=app.querySelector('.agenda');
  const mounted=Boolean(quickOverviewOpen && calendarView==='month' && !selectedEvent && !selectedPlan && !settingsReturn && !activePlansOverview);
  agenda?.classList.toggle('quick-agenda-mode',mounted);
  if(mounted){agenda.append(sheet);agenda.setAttribute('aria-label','전체 학습 현황');}
  else {const phone=document.querySelector('.phone');if(sheet.parentElement && sheet.parentElement!==phone)phone.append(sheet);agenda?.setAttribute('aria-label','선택 날짜의 일정');}
}
function toggleQuickOverview(date=null){
  agendaModeMotion?.finish(true);
  const target=date?false:!quickOverviewOpen;
  const change=()=>{
    if(date){
      selectedDate=date;
      document.querySelector('.phone').append(document.querySelector('#quick-overview-sheet'));
      app.querySelector('.agenda').innerHTML=renderMonthAgenda(date);
      app.querySelector('.grid').innerHTML=renderMonthCells(viewDate);
      app.querySelector('.agenda-list').scrollTop=0;
    }
    quickOverviewOpen=target;
    if((target || date) && calendarView==='month')setMonthAgendaExpanded(true);
    syncQuickOverview();
  };
  const content=()=>quickOverviewOpen?[document.querySelector('.quick-overview-heading'),document.querySelector('#quick-overview-graphs')]:[app.querySelector('.agenda-date'),app.querySelector('.agenda-list')];
  const outgoing=content().filter(Boolean);
  if(calendarView!=='month' || window.matchMedia('(prefers-reduced-motion: reduce)').matches || !outgoing.every(node=>node.animate)){change();return;}
  let changed=false,finished=false;const animations=[];
  const motion={finish(commit){if(finished)return;finished=true;animations.forEach(animation=>animation.cancel());if(commit && !changed){changed=true;change();}if(agendaModeMotion===motion)agendaModeMotion=null;}};
  agendaModeMotion=motion;
  const fadeOut=outgoing.map(node=>node.animate([{opacity:1,transform:'translateY(0)'},{opacity:0,transform:'translateY(-3px)'}],{duration:130,easing:'ease-in',fill:'both'}));
  animations.push(...fadeOut);
  Promise.all(fadeOut.map(animation=>animation.finished.catch(()=>{}))).then(()=>{
    if(finished)return;
    changed=true;change();fadeOut.forEach(animation=>animation.cancel());
    const fadeIn=content().filter(Boolean).map(node=>node.animate([{opacity:0,transform:'translateY(5px)'},{opacity:1,transform:'translateY(0)'}],{duration:190,easing:'cubic-bezier(.22,.75,.18,1)',fill:'both'}));
    animations.push(...fadeIn);
    Promise.all(fadeIn.map(animation=>animation.finished.catch(()=>{}))).then(()=>motion.finish(false));
  });
}
function clearQuickStatusOnCalendar(event){
  if(!quickStatusFilter || !quickOverviewOpen || event.target.closest?.('#quick-overview-sheet'))return;
  if(!event.target.closest?.('#app'))return;
  quickStatusFilter=null;paintQuickStatusHighlight();
  document.querySelector('#quick-overview-graphs').querySelectorAll('[aria-pressed]').forEach(node=>node.setAttribute('aria-pressed','false'));
}
function paintQuickStatusHighlight(){
  app.querySelectorAll('[data-event]').forEach(node=>{
    const event=events.find(e=>e.id===node.dataset.event);
    node.classList.toggle('quick-status-highlight',Boolean(quickOverviewOpen && quickStatusFilter && event?.type==='study' && event.status===quickStatusFilter && planFor(event)?.active));
  });
}
function selectQuickStatus(status){
  if(!['all','done','partial','missed','unknown'].includes(status))return;
  quickStatusFilter=status==='all' || quickStatusFilter===status?null:status;syncQuickOverview();
}
function syncQuickOverview(mount=true){
  if(selectedEvent || selectedPlan || settingsReturn || activePlansOverview || planCreation || planCalendarPreview || normalConflictDay || searchOpen)quickOverviewOpen=false;
  const sheet=document.querySelector('#quick-overview-sheet');
  sheet.classList.toggle('visible',quickOverviewOpen);sheet.inert=!quickOverviewOpen || Boolean(phoneDialogBackgroundLock);sheet.setAttribute('aria-hidden',String(!quickOverviewOpen));
  document.querySelector('.phone').classList.toggle('quick-overview-open',quickOverviewOpen);
  const height=(document.querySelector('.phone').clientHeight || 892)/2;
  document.querySelector('.phone').style.setProperty('--quick-sheet-height',`${height}px`);
  document.querySelector('.phone').style.setProperty('--quick-calendar-height',`${Math.max(130,monthAgendaLimits().available-height)}px`);
  if(!quickOverviewOpen)quickStatusFilter=null;
  paintQuickStatusHighlight();
  app.querySelector('[data-action="quick-overview"]')?.setAttribute('aria-expanded',String(quickOverviewOpen));
  if(quickOverviewOpen){
    const stats=activePlansStatistics(),counts=Object.fromEntries(['done','partial','missed','unknown'].map(key=>[key,stats.studies.filter(e=>e.status===key).length]));
    document.querySelector('#quick-overview-graphs').innerHTML=`<div class="quick-progress sidebar-plan-progress ${stats.progress<stats.expectedProgress?'behind':stats.progress>stats.expectedProgress?'ahead':'on-track'}" role="group" aria-label="전체 학습 진행률"><div class="quick-progress-values"><span>전체 진행률</span><strong>${stats.progress}%</strong></div>${renderProgressTrack(stats)}</div><div class="quick-status-summary">${renderTopicStatusChart(counts,stats.studies.length,'전체 활성 학습 일정 상태별 개수',true,quickStatusFilter)}<div class="quick-status-key">${['done','partial','missed','unknown'].map(key=>`<button type="button" data-topic-status="${key}" aria-pressed="${quickStatusFilter===key}"><i style="background:${STATUS[key].color}"></i>${STATUS[key].label} <b>${counts[key]}</b></button>`).join('')}</div></div>`;
  }
  if(mount)mountQuickOverview();
}
document.querySelector('#quick-overview-sheet').addEventListener('pointerdown',event=>{
  if(event.button!==0 || !quickOverviewOpen)return;
  event.stopPropagation?.();
  quickOverviewDrag={id:event.pointerId,y:event.clientY};
});
document.querySelector('#quick-overview-sheet').addEventListener('pointermove',event=>{
  if(!quickOverviewDrag || quickOverviewDrag.id!==event.pointerId)return;
  const distance=Math.max(0,event.clientY-quickOverviewDrag.y);
  if(distance>6 && !event.currentTarget.hasPointerCapture(event.pointerId))event.currentTarget.setPointerCapture(event.pointerId);
  event.currentTarget.style.setProperty('--quick-drag',`${distance}px`);
});
function endQuickOverviewDrag(event,cancel=false){
  if(!quickOverviewDrag || quickOverviewDrag.id!==event.pointerId)return;
  const distance=event.clientY-quickOverviewDrag.y;quickOverviewDrag=null;
  event.currentTarget.style.setProperty('--quick-drag','0px');
  if(event.currentTarget.hasPointerCapture(event.pointerId))event.currentTarget.releasePointerCapture(event.pointerId);
  if(!cancel && distance>45){hideQuickOverview();if(calendarView==='month')setMonthAgendaExpanded(false);}
  if(Math.abs(distance)>6)quickClickUntil=Date.now()+400;
}
document.querySelector('#quick-overview-graphs').addEventListener('keydown',event=>{
  const status=event.target.closest('[data-topic-status]');
  if(status && status.tagName?.toLowerCase()!=='button' && ['Enter',' '].includes(event.key)){event.preventDefault();selectQuickStatus(status.dataset.topicStatus);}
});
document.querySelector('#quick-overview-graphs').addEventListener('click',event=>{
  if(Date.now()<quickClickUntil)return;
  const status=event.target.closest('[data-topic-status]')?.dataset.topicStatus;if(status)selectQuickStatus(status);
});
document.querySelector('#quick-overview-details').addEventListener('click',()=>{if(Date.now()>=quickClickUntil)openActivePlansOverview('calendar');});
document.querySelector('#quick-overview-sheet').addEventListener('pointerup',event=>endQuickOverviewDrag(event));
document.querySelector('#quick-overview-sheet').addEventListener('pointercancel',event=>endQuickOverviewDrag(event,true));
function activePlansStatistics(){
  const active=plans.filter(plan=>plan.active),studies=events.filter(e=>e.type==='study' && active.some(plan=>plan.id===(e.planId || 'engineer')));
  const minutes=studies.reduce((sum,e)=>sum+studyWorkloadMinutes(e),0),completedMinutes=studies.reduce((sum,e)=>sum+studyWorkloadMinutes(e)*progressForEvent(e)/100,0),expectedMinutes=studies.filter(e=>e.date<=TODAY).reduce((sum,e)=>sum+studyWorkloadMinutes(e),0);
  return {active,studies,minutes,completedMinutes,progress:minutes?Math.round(completedMinutes/minutes*100):0,expectedProgress:minutes?Math.round(expectedMinutes/minutes*100):0};
}
async function openActivePlansOverview(source='sidebar'){
  if(activePlansOverview)return;
  const sidebarScroll=document.querySelector('.plans-panel-content').scrollTop;
  const scrolls=['.time-scroll','.agenda-list','.year-view'].map(selector=>({selector,top:app.querySelector(selector)?.scrollTop || 0}));
  await closePlansDrawer();
  const calendar={calendarView,selectedDate,viewDate:new Date(viewDate),monthAgendaExpanded},quickFilter=quickStatusFilter;
  animateDetailNavigation(()=>{activePlansOverview={sidebarScroll,scrolls,expanded:false,source:source==='calendar'?'calendar':'sidebar',calendar,quickFilter};renderActivePlansOverview();},false,true);
}
function closeActivePlansOverview(){
  const saved=activePlansOverview;if(!saved)return;
  animateDetailNavigation(()=>{activePlansOverview=null;if(saved.calendar)({calendarView,selectedDate,viewDate,monthAgendaExpanded}=saved.calendar);quickOverviewOpen=saved.source==='calendar';quickStatusFilter=saved.quickFilter || null;renderCalendar();saved.scrolls.forEach(({selector,top})=>{const node=app.querySelector(selector);if(node)node.scrollTop=top;});},true,true);
  if(saved.source==='calendar')app.querySelector('[data-action="quick-overview"]')?.focus({preventScroll:true});
  else {openPlansDrawer();document.querySelector('.plans-panel-content').scrollTop=saved.sidebarScroll;document.querySelector('#active-plans-overview').focus({preventScroll:true});}
}
function renderActivePlansOverview(){
  hideQuickOverview();
  const stats=activePlansStatistics(),keys=['done','partial','missed','unknown'],counts=Object.fromEntries(keys.map(key=>[key,stats.studies.filter(e=>(STATUS[e.status]?e.status:'unknown')===key).length]));
  const filter=activePlansOverview.statusFilter || null;
  const studies=stats.studies.filter(e=>!filter || (STATUS[e.status]?e.status:'unknown')===filter).sort((a,b)=>a.date.localeCompare(b.date)||a.start.localeCompare(b.start));
  app.innerHTML=`<article class="detail plan-detail active-plans-detail"><header class="toolbar">${iconButton('active-plans-back',activePlansOverview.source==='calendar'?'달력과 하단 현황으로 돌아가기':'사이드바로 돌아가기','back')}<h2>전체 학습 현황</h2></header><div class="detail-scroll"><div class="detail-body"><div class="detail-heading"><h1>활성 학습 계획</h1><p class="mobile-help">활성화된 ${stats.active.length}개 계획의 학습 일정을 합산했어요.</p></div><section class="card plan-overview">${renderProgressComparison(stats)}<p class="mobile-help">전체 배정 시간 ${planTimeLabel(stats.minutes)} · 학습 반영 시간 ${planTimeLabel(stats.completedMinutes)}</p></section><section class="card topic-status-card plan-status-overview"><h2>전체 일정 상태별 개수</h2><div class="topic-chart-layout">${renderTopicStatusChart(counts,stats.studies.length,'활성 계획 전체 일정 상태별 개수',true,filter)}<div class="topic-status-legend" role="group" aria-label="일정 상태 필터">${keys.map(key=>`<button type="button" data-topic-status="${key}" aria-pressed="${filter===key}"><span><i style="background:${STATUS[key].color}"></i>${STATUS[key].label}</span><strong>${counts[key]}개</strong></button>`).join('')}</div></div></section><h2 class="section-heading plan-schedule-heading"><button type="button" data-action="toggle-active-schedules" aria-expanded="${activePlansOverview.expanded}" aria-controls="active-schedules-panel"><span>전체 학습 일정 <span class="topic-event-count">${studies.length}개${filter?` / 전체 ${stats.studies.length}개`:''}</span></span><span class="plan-schedule-chevron" aria-hidden="true">${activePlansOverview.expanded?'⌃':'⌄'}</span></button></h2><div id="active-schedules-panel" class="plan-schedules-panel${activePlansOverview.expanded?' expanded':''}" ${activePlansOverview.expanded?'':'inert aria-hidden="true"'}><div class="detail-schedule-content"><section class="card plan-upcoming plan-schedule-list">${studies.length?studies.map(e=>`<button type="button" data-event="${escapeHTML(e.id)}"><span><strong>${escapeHTML(e.title)}</strong><small>${escapeHTML(planFor(e)?.name || '')}<br>${longDate(e.date)} · ${e.start}–${e.end}</small><span class="plan-schedule-status" style="color:${(STATUS[e.status]||STATUS.unknown).color}">${(STATUS[e.status]||STATUS.unknown).label}</span></span><span aria-hidden="true">›</span></button>`).join(''):`<p class="empty">${filter?STATUS[filter].label+' 상태의 일정이 없어요.':'활성화된 학습 일정이 없어요.'}</p>`}</section></div></div></div></div></article>`;
}
document.querySelector('#active-plans-overview').addEventListener('click',openActivePlansOverview);
function filterActiveSchedules(status){
  if(!activePlansOverview || !['all','done','partial','missed','unknown'].includes(status))return;
  const scroll=app.querySelector('.detail-scroll')?.scrollTop || 0;
  activePlansOverview.statusFilter=status==='all' || activePlansOverview.statusFilter===status?null:status;
  activePlansOverview.expanded=true;
  renderActivePlansOverview();app.querySelector('.detail-scroll').scrollTop=scroll;
}
let settingsReturn=null;
async function openSettings(){
  if(settingsReturn)return;
  const saved={sidebarScroll:document.querySelector('.plans-panel-content').scrollTop,scrolls:['.time-scroll','.agenda-list','.year-view'].map(selector=>({selector,top:app.querySelector(selector)?.scrollTop || 0}))};
  await closePlansDrawer();
  animateDetailNavigation(()=>{settingsReturn=saved;renderSettings();},false,true);
  app.querySelector('[data-action="settings-back"]')?.focus({preventScroll:true});
}
function renderSettings(){
  hideQuickOverview();
  app.innerHTML=`<article class="detail settings-detail"><header class="toolbar">${iconButton('settings-back','사이드바로 돌아가기','back')}<h2>설정</h2></header><div class="detail-scroll"><div class="detail-body"><button type="button" class="settings-sync-button card" data-action="calendar-sync">${svg('calendar')}<span>캘린더 동기화</span><span aria-hidden="true">›</span></button><section class="card"><h3>앱 정보</h3><p class="mobile-help">라이센스 플래너 · 학습 달력</p></section></div></div></article>`;
}
function openCalendarSync(){
  document.querySelector('#sync-calendar-select').value='';
  document.querySelector('#confirm-sync-calendar').disabled=true;
  showPhoneDialog('#sync-calendar');
}
document.querySelector('#sync-calendar-select').addEventListener('change',event=>{
  document.querySelector('#confirm-sync-calendar').disabled=!event.target.value;
});
document.querySelector('#cancel-sync-calendar').addEventListener('click',()=>closePhoneDialog('#sync-calendar'));
document.querySelector('#sync-calendar-form').addEventListener('submit',async event=>{
  event.preventDefault();
  if(!document.querySelector('#sync-calendar-select').value)return;
  await closePhoneDialog('#sync-calendar');
  toast('동기화 미리보기예요. 실제 캘린더에는 반영되지 않아요.');
});
function closeSettings(){
  if(!settingsReturn)return;
  const saved=settingsReturn;
  animateDetailNavigation(()=>{settingsReturn=null;renderCalendar();saved.scrolls.forEach(({selector,top})=>{const node=app.querySelector(selector);if(node)node.scrollTop=top;});},true,true);
  openPlansDrawer();document.querySelector('.plans-panel-content').scrollTop=saved.sidebarScroll;
  document.querySelector('#plans-settings').focus({preventScroll:true});
}
document.querySelector('#plans-settings').addEventListener('click',openSettings);
function openPlansDrawer() {
  if (plansDrawerClosing || plansDrawerOpen) return;
  renderPlanList();renderViewSelector();
  plansDrawerOpen = true;
  document.querySelector('#plans-drawer').hidden = false;
  app.inert = true;
  app.querySelector('[data-action="plans"]')?.setAttribute('aria-expanded','true');
  document.querySelector('#close-plans').focus({preventScroll:true});
}
function closePlansDrawer() {
  if (plansDrawerClosing) return plansDrawerClosing;
  if (!plansDrawerOpen) return Promise.resolve();
  const layer = document.querySelector('#plans-drawer');
  const panel = document.querySelector('#plans-dialog');
  const backdrop = document.querySelector('#plans-backdrop');
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  layer.classList.add('closing');
  const current = getComputedStyle(panel), shade = getComputedStyle(backdrop);
  const options = {duration:280, easing:'cubic-bezier(.4,0,.7,.3)', fill:'forwards'};
  const animations = !reduced && panel.animate ? [
    panel.animate([{transform:current.transform},{transform:'translateX(-105%)'}],options),
    backdrop.animate([{opacity:shade.opacity},{opacity:0}],options)
  ] : [];
  plansDrawerClosing = Promise.all(animations.map(animation=>animation.finished.catch(()=>{}))).then(()=>{
    layer.hidden = true;
    animations.forEach(animation=>animation.cancel());
    layer.classList.remove('closing');
    plansDrawerOpen = false; plansDrawerClosing = null;
    app.inert = false;
    const menu = app.querySelector('[data-action="plans"]');
    menu?.setAttribute('aria-expanded','false'); menu?.focus({preventScroll:true});
  });
  return plansDrawerClosing;
}
document.querySelector('#drawer-conflicts').addEventListener('click',async event=>{
  const button=event.target.closest('[data-action="calendar-conflicts"]');
  if(!button || !calendarConflicts().length)return;
  await closePlansDrawer();showCalendarConflicts();
});

document.querySelector('#plans-backdrop').addEventListener('click', closePlansDrawer);
document.querySelector('#close-plans').addEventListener('click', closePlansDrawer);
document.querySelector('#plans-drawer').addEventListener('keydown', action=>{
  if (action.key === 'Escape') { action.preventDefault(); closePlansDrawer(); return; }
  if (action.key !== 'Tab') return;
  const controls = [...document.querySelector('#plans-dialog').querySelectorAll('button:not(:disabled)')];
  if (!controls.length) return;
  action.preventDefault();
  const index = controls.indexOf(document.activeElement);
  controls[(index + (action.shiftKey ? -1 : 1) + controls.length) % controls.length].focus();
});
function setPlanActive(id, active) {
  const plan = plans.find(plan=>plan.id === id);
  if (!plan) return;
  const inPeriod=(!plan.startDate || plan.startDate<=TODAY) && (!plan.examDate || TODAY<=plan.examDate);
  plan.active=Boolean(active) && inPeriod;
  planActivityState.set(plan,{manual:Boolean(active),applied:plan.active});
  selectedEvent = null; searchOpen = false;
  renderCalendar(); renderPlanList();
}
document.querySelector('#toggle-plans-list').addEventListener('click',()=>{
  const toggle=document.querySelector('#toggle-plans-list'),panel=document.querySelector('#plans-list-panel');
  const expanded=toggle.getAttribute('aria-expanded')!=='true';
  toggle.setAttribute('aria-expanded',String(expanded));
  panel.classList.toggle('collapsed',!expanded);panel.inert=!expanded;
});
document.querySelector('#plans-list').addEventListener('click', async action => {
  const edit=action.target.closest('[data-edit-exam]');
  if(edit?.dataset.editExam){await closePlansDrawer();openExamEditor(edit.dataset.editExam);return;}
  const toggle = action.target.closest('[data-plan-toggle]');
  if (!toggle?.dataset.planToggle){
    const button=action.target.closest('[data-open-plan]'),row=action.target.closest('[data-plan-detail]');
    const id=button?.dataset.openPlan || row?.dataset.planDetail;
    if(id)await openPlanDetail(id);
    return;
  }
  const plan = plans.find(plan=>plan.id === toggle.dataset.planToggle);
  if (!plan) return;
  setPlanActive(plan.id, !plan.active);
  document.querySelector(`#plans-list [data-plan-toggle="${plan.id}"]`)?.focus();
});
function openAddSchedule() {
  const activePlans = plans.filter(plan=>plan.active);
  if (!activePlans.length) { toast('계획 목록에서 학습 계획을 먼저 활성화해주세요.'); return; }
  const form = document.querySelector('#add-form');
  form.elements.planId.innerHTML = activePlans.map(plan=>`<option value="${plan.id}">${escapeHTML(plan.name)}</option>`).join('');
  form.elements.date.value = selectedDate;
  document.querySelector('#form-error').textContent = '';
  showPhoneDialog('#add');
}
app.addEventListener('click', event => {
  if(selfDiagnosis){handleSelfDiagnosisClick(event);return;}
  const historical=event.target.closest('[data-diagnosis-index]');
  if(selectedPlan && historical?.dataset.diagnosisIndex!==undefined){openDiagnosisHistory(Number(historical.dataset.diagnosisIndex));return;}
  clearQuickStatusOnCalendar(event);
  if(activePlansOverview && !selectedEvent){
    const status=event.target.closest('[data-topic-status]')?.dataset.topicStatus;
    if(status){filterActiveSchedules(status);return;}
    const button=event.target.closest('button');
    if(button?.dataset.action==='active-plans-back')closeActivePlansOverview();
    else if(button?.dataset.action==='toggle-active-schedules'){
      const scroll=app.querySelector('.detail-scroll')?.scrollTop || 0;
      activePlansOverview.expanded=!activePlansOverview.expanded;renderActivePlansOverview();app.querySelector('.detail-scroll').scrollTop=scroll;
    }else if(button?.dataset.event)openEvent(button.dataset.event);
    return;
  }
  if(settingsReturn){const action=event.target.closest('button')?.dataset.action;if(action==='settings-back')closeSettings();else if(action==='calendar-sync')openCalendarSync();return;}
  dismissReplanHighlight(event);
  const returnAction=event.target.closest('button')?.dataset.action;
  if((topicCalendarReturn || conflictCalendarReturn) && !['return-topic','return-conflicts'].includes(returnAction))clearCalendarReturns();
  if(planCreation){
    if(planCreation.preview && pendingMove){if(Date.now()<suppressEventClickUntil)return;if(event.target.closest('[data-reposition]')){movePicking=true;return;}return;}
    handlePlanCreationClick(event);return;
  }
  if (Date.now() < suppressEventClickUntil) { event.preventDefault(); return; }
  const button = event.target.closest('button');
  if (pendingMove) {
    event.preventDefault();
    if (moveClosing || pointerMove?.active) return;
    if (button?.dataset.reposition) {
      movePicking = true;
      keyboardMove = {id:pendingMove.id, date:pendingMove.date};
      paintMovePreview(movePreviewEvent(pendingMove.id), pendingMove.date, true);
      app.querySelector('[data-reposition]')?.focus({preventScroll:true});
      toast('다른 날짜를 누르거나 드래그해주세요');
    } else if (movePicking && button?.dataset.date) {
      if (!openMovePreview(pendingMove.id, button.dataset.date)) cancelMove();
    } else cancelMove();
    return;
  }
  const statusFilter=event.target.closest('[data-topic-status]');
  if(selectedPlan && statusFilter?.dataset.topicStatus){toggleTopicStatus(statusFilter.dataset.topicStatus);return;}
  const topic=event.target.closest('[data-plan-topic]');
  if(selectedPlan && topic?.dataset.planTopic!==undefined){openPlanTopic(topic.dataset.planTopic);return;}
  if(calendarView==='month' && !selectedPlan && !selectedEvent && !searchOpen && !planCalendarPreview && !normalConflictDay && monthAgendaExpanded && !button){
    const surface=event.target.closest('.grid');
    if(surface?.classList?.contains('grid'))setMonthAgendaExpanded(false);
  }
  if (!button) return;
  if(button.dataset.action==='calendar-conflicts'){showCalendarConflicts();return;}
  if(button.dataset.locateEvent){locatePlanEvent(button.dataset.locateEvent);return;}
  if(normalConflictDay)return;
  if(planCalendarPreview){
    if(button.dataset.event && !planTimeEdit){startPlanTimeEdit(button.dataset.event);return;}
    if(planTimeEdit)return;
    if(button.dataset.action==='prev' || button.dataset.action==='next')navigateCalendar(button.dataset.action==='prev'?-1:1);
    else if(button.dataset.date){selectedDate=button.dataset.date;renderCalendar();}
    else if(button.dataset.action==='today'){viewDate=parseDate(TODAY);selectedDate=TODAY;renderCalendar();}
    return;
  }
  if (button.dataset.editExam) {openExamEditor(button.dataset.editExam);return;}
  if (button.dataset.miniMonth) {viewDate=parseDate(button.dataset.miniMonth);selectedDate=button.dataset.miniMonth;setCalendarView('month');return;}
  if (button.dataset.miniDate || button.dataset.openDay) {selectedDate=button.dataset.miniDate||button.dataset.openDay;viewDate=parseDate(selectedDate);setCalendarView('day');return;}
  if (button.dataset.event) { openEvent(button.dataset.event); return; }
  if (button.dataset.status) { setStatus(button.dataset.status); return; }
  if (button.dataset.date) {
    if(calendarView==='month' && (quickOverviewOpen || agendaModeMotion)){toggleQuickOverview(button.dataset.date);return;}
    const expandAgenda=calendarView==='month';
    const content=expandAgenda && selectedDate!==button.dataset.date?captureMonthAgendaContent():null;
    const geometry=expandAgenda?{sheet:app.querySelector('.agenda')?.getBoundingClientRect?.().height,grid:app.querySelector('.grid')?.getBoundingClientRect?.().height}:null;
    if(expandAgenda)stopMonthAgendaMotion();
    selectedDate=button.dataset.date;renderCalendar();
    if(expandAgenda)setMonthAgendaExpanded(true,geometry);
    if(content){const list=app.querySelector('.agenda-list');if(list)list.scrollTop=0;animateMonthAgendaContent(content);}
    app.querySelector(`[data-date="${selectedDate}"]`)?.focus({preventScroll:true});return;
  }
  const action = button.dataset.action;
  if(action==='quick-overview'){toggleQuickOverview();return;}
  if(action==='self-diagnosis'){openSelfDiagnosis();return;}
  if(action==='diagnose-plan'){openSelfDiagnosis(selectedPlan);return;}
  if(action==='diagnosis-records-details'){openDiagnosisRecords();return;}
  if(action==='diagnosis-records-back'){closeDiagnosisRecords();return;}
  if (action === 'toggle-month-agenda' && Date.now()>=monthAgendaClickUntil)setMonthAgendaExpanded(!monthAgendaExpanded);
  if(action==='rename-plan')openPlanManagement('rename');
  if(action==='delete-plan')openPlanManagement('delete');
  if(action==='delete-study')openStudyDeletion();
  if (action === 'replan-remaining') askReplanDiagnosis(selectedPlan);
  if (action === 'export-plan-calendar') openPlanExport();
  if (action === 'toggle-plan-schedules') togglePlanSchedules();
  if (action === 'edit-schedule') openScheduleEditor();
  if (action === 'return-conflicts') returnToConflictList();
  if (action === 'return-topic') returnToPlanTopic();
  if(action==='topic-allocation-details')openPlanSection('topics');
  if(action==='plan-status-details')openPlanSection('status');
  if(action==='allocation-back' || action==='plan-section-back')closePlanSection();
  if (action === 'topic-back')returnFromPlanTopic();
  if (action === 'plan-back') closePlanDetail();
  if (action === 'dismiss-conflict') dismissConflict();
  if (action === 'manual-fix') startManualMove();
  if (action === 'auto-fix') autoFixSchedule();
  if (action === 'back')closeEventDetail();
  if (action === 'prev' || action === 'next') {navigateCalendar(action==='prev'?-1:1);app.querySelector(`[data-action="${action}"]`)?.focus();}
  if (action === 'today') { viewDate = parseDate(TODAY); selectedDate = TODAY; searchOpen = false; renderCalendar(); }
  if (action === 'search') { searchOpen = !searchOpen; renderCalendar(); }
  if (action === 'plans') openPlansDrawer();
  if (action === 'info') showPhoneDialog('#info');
  if (action === 'add') setFabCreateMenu(!fabCreateMenuOpen);
});
function updateProgress(control) {
  const e = events.find(e => e.id === selectedEvent);
  if (!e || e.type !== 'study' || control.value === '' || !Number.isFinite(control.valueAsNumber)) return;
  e.progress = normalizedProgress(control.valueAsNumber);
  e.status = statusFromProgress(e.progress);
  const range = app.querySelector('#progress-range'), number = app.querySelector('#progress-number');
  range.value = e.progress; number.value = e.progress;
  range.style.setProperty('--progress', `${e.progress}%`);
  range.setAttribute('aria-valuetext', `${e.progress}퍼센트`);
  // Update in place so dragging and typing retain focus and pointer capture.
  const badge = app.querySelector('.detail-status');
  badge.className = `detail-status ${e.status}`;
  badge.textContent = STATUS[e.status].label;
  app.querySelector('.result-note').textContent = STATUS[e.status].note;
  app.querySelectorAll('[data-status]').forEach(button => {
    const checked = button.dataset.status === e.status;
    button.setAttribute('aria-checked', String(checked));
    button.tabIndex = checked ? 0 : -1;
  });
}
app.addEventListener('input', event => {
  if(selfDiagnosis){
    const index=Number(event.target.dataset.selfQuestion),value=Number(event.target.value);
    if(event.target.dataset.selfQuestion!==undefined && Number.isInteger(index) && index>=0 && index<selfDiagnosis.answers.length && Number.isInteger(value) && value>=0 && value<=3){
      selfDiagnosis.answers[index]=value;
      app.querySelector('#submit-self-diagnosis').disabled=selfDiagnosis.answers.some(answer=>answer===null);
    }
    return;
  }
  if(planCreation){handlePlanCreationInput(event);return;}
  if (event.target.id === 'search-input') renderSearch(event.target.value.trim());
  if (['progress-range', 'progress-number'].includes(event.target.id)) updateProgress(event.target);
});
app.addEventListener('focusout', event => {
  if (event.target.id !== 'progress-number') return;
  const e = events.find(e => e.id === selectedEvent);
  if (e) event.target.value = normalizedProgress(e.progress);
});
app.addEventListener('keydown', event => {
  const historyPoint=event.target?.closest?.('[data-diagnosis-index]');
  if(selectedPlan && !selfDiagnosis && historyPoint?.dataset.diagnosisIndex!==undefined && ['Enter',' '].includes(event.key)){event.preventDefault();openDiagnosisHistory(Number(historyPoint.dataset.diagnosisIndex));return;}
  if(selfDiagnosis && event.key==='Escape'){event.preventDefault();closeSelfDiagnosis();return;}
  if(fabCreateMenuOpen && event.key==='Escape'){event.preventDefault();setFabCreateMenu(false);app.querySelector('.fab')?.focus({preventScroll:true});return;}
  if(activePlansOverview && !selectedEvent && ['Enter',' '].includes(event.key)){
    const status=event.target.closest('[data-topic-status]');
    if(status && status.tagName?.toLowerCase()!=='button'){event.preventDefault();filterActiveSchedules(status.dataset.topicStatus);return;}
  }
  if(activePlansOverview && !selectedEvent && event.key==='Escape'){event.preventDefault();closeActivePlansOverview();return;}
  if(settingsReturn){if(event.key==='Escape'){event.preventDefault();closeSettings();}return;}
  const allocationTopic=event.target?.closest?.('[data-plan-topic]');
  if(selectedPlan && allocationTopic?.tagName?.toLowerCase()==='circle' && ['Enter',' '].includes(event.key)){event.preventDefault();openPlanTopic(allocationTopic.dataset.planTopic);return;}
  const status=event.target?.closest?.('[data-topic-status]');
  if(selectedPlan && status?.dataset.topicStatus && ['Enter',' '].includes(event.key) && status.tagName?.toLowerCase()!=='button'){event.preventDefault();toggleTopicStatus(status.dataset.topicStatus);return;}
  if(planCreation){if(event.key==='Escape'){event.preventDefault();if(pointerMove)clearPointerMove();else if(pendingMove)cancelMove();else if(planCreation.replanComparison){planCreation.replanComparison=false;planCreation.preview=null;renderPlanCreation();}else if(planCreation.preview)cancelCreationPreview();else cancelPlanCreation();}return;}
  if (handleMoveKey(event)) return;
  if (event.target.id === 'progress-number' && event.key === 'Enter') { event.preventDefault(); event.target.blur(); return; }
  if (event.key === 'Escape') {
    if (selectedEvent)closeEventDetail();
    else if (searchOpen) { searchOpen = false; renderCalendar(); }
    else if(selectedPlan){if(diagnosisRecordsReturn)closeDiagnosisRecords();else if(selectedTopic!==null)returnFromPlanTopic();else if(planTopicsOverview || planStatusOverview)closePlanSection();else closePlanDetail();}
    else if(conflictCalendarReturn)returnToConflictList();
    else if(topicCalendarReturn)returnToPlanTopic();
  }
  const radio = event.target.closest('[data-status]');
  if (!radio || !['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
  event.preventDefault(); const keys = ['done', 'partial', 'missed'], index = keys.indexOf(radio.dataset.status);
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index + (['ArrowDown', 'ArrowRight'].includes(event.key) ? 1 : -1) + 3) % 3;
  setStatus(keys[next]);
});
document.querySelector('#cancel-add').addEventListener('click', () => closePhoneDialog('#add'));
document.querySelector('#add-form').addEventListener('submit', event => {
  event.preventDefault(); const form = event.currentTarget, values = new FormData(form);
  const start = values.get('start'), end = values.get('end');
  const planId = values.get('planId');
  if (!plans.some(plan=>plan.id === planId && plan.active)) { document.querySelector('#form-error').textContent = '활성화된 계획을 선택해주세요.'; return; }
  if (start >= end) { document.querySelector('#form-error').textContent = '종료 시간은 시작 시간보다 늦게 선택해주세요.'; return; }
  if (!values.get('title').trim() || !values.get('content').trim()) { document.querySelector('#form-error').textContent = '학습명과 내용을 입력해주세요.'; return; }
  events.push({ id: `custom-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, planId, title: values.get('title').trim(), date: values.get('date'), start, end, content: values.get('content').trim(), status: 'unknown', type: 'study' });
  selectedDate = values.get('date'); viewDate = parseDate(selectedDate); form.reset(); fabScheduleReturn=null;closePhoneDialog('#add'); renderCalendar(); toast('새 학습 일정을 추가했어요');
});
document.querySelector('#reset').addEventListener('click', () => {
  if (!confirm('변경한 학습 결과와 추가한 일정을 지우고 예시 데이터로 되돌릴까요?')) return;
  endCreationBrush(null,true);clearCreationUndo();clearDeletedPlanUndo();clearDeletedStudyUndo();diagnosisRecordsReturn=null;selfDiagnosis=null;activePlansOverview=null;eventDetailReturn=null;planCreation=null;selectedPlan=null;selectedTopic=null;topicDetailReturn=null;planTopicsOverview=false;planStatusOverview=false;planSectionReturn=null;topicStatusFilter=null;planStatusFilter=null;planSchedulesExpanded=false;monthAgendaExpanded=false;monthAgendaGesture=null;stopMonthAgendaMotion();planDetailReturnView=null;topicCalendarReturn=null;conflictCalendarReturn=null;calendarSpotlight=null;clearTimeout(calendarSpotlightTimer);plans = makePlans(); events = makeSeeds(); events.forEach(e => { e.progress = progressForEvent(e); }); selectedEvent = null; viewDate = parseDate(TODAY); selectedDate = TODAY; calendarView='month';searchOpen = false; renderCalendar(); toast('예시 데이터를 초기화했어요');
});
let remainingReplanSequence=0;
function studyWorkloadMinutes(event){return event.workloadMinutes ?? Math.max(0,minutesOfDay(event.end)-minutesOfDay(event.start));}
function remainingWorkMinutes(event){return Math.ceil(studyWorkloadMinutes(event)*(100-progressForEvent(event))/100);}
function remainingReplanSources(id){
  const future=remainingPlanEvents(id).filter(e=>remainingWorkMinutes(e)>0);
  const history=events.filter(e=>e.type==='study' && e.planId===id && e.date<TODAY && ['missed','partial'].includes(e.status)).map(record=>{
    const allocated=events.filter(e=>e.type==='study' && e.planId===id && e.recoverySourceId===record.id).reduce((sum,e)=>sum+studyWorkloadMinutes(e),0);
    const remaining=Math.max(0,Math.round(remainingWorkMinutes(record)-allocated));
    return {...record,historySourceId:record.id,status:'unknown',progress:0,workloadMinutes:remaining};
  }).filter(e=>e.workloadMinutes>0);
  return [...history,...future].sort((a,b)=>a.date.localeCompare(b.date)||a.start.localeCompare(b.start)||a.id.localeCompare(b.id));
}
function remainingSourceSignature(id){return JSON.stringify(events.filter(e=>e.type==='study' && e.planId===id).map(e=>[e.id,e.date,e.start,e.end,e.status,e.progress,e.workloadMinutes,e.recoverySourceId]).sort((a,b)=>a[0].localeCompare(b[0])));}
let replanDiagnosisPlanId=null;
function askReplanDiagnosis(id){
  const plan=plans.find(item=>item.id===id);if(!plan || planCreation || pointerMove || pendingMove)return;
  if(!remainingReplanSources(id).length){openRemainingReplan(id);return;}
  replanDiagnosisPlanId=id;
  document.querySelector('#replan-diagnosis-plan').textContent=plan.name;
  document.querySelector('#replan-with-diagnosis').disabled=!plan.active;
  showPhoneDialog('#replan-diagnosis-prompt');
}
async function chooseReplanDiagnosis(diagnose){
  const id=replanDiagnosisPlanId;replanDiagnosisPlanId=null;
  if(!id)return;
  await closePhoneDialog('#replan-diagnosis-prompt');
  if(!plans.some(plan=>plan.id===id))return;
  if(diagnose)openSelfDiagnosis(id);else openRemainingReplan(id);
}
document.querySelector('#replan-with-diagnosis').addEventListener('click',()=>chooseReplanDiagnosis(true));
document.querySelector('#replan-without-diagnosis').addEventListener('click',()=>chooseReplanDiagnosis(false));
document.querySelector('#cancel-replan-diagnosis').addEventListener('click',()=>{replanDiagnosisPlanId=null;closePhoneDialog('#replan-diagnosis-prompt');});
function openRemainingReplan(id){
  const plan=plans.find(plan=>plan.id===id),remaining=remainingReplanSources(id);
  if(!plan || pendingMove || pointerMove || planCreation)return;
  if(!remaining.length){toast(pastUnfinishedEvents(id).some(e=>e.status==='unknown')?'과거 미확인 일정의 학습 결과를 먼저 기록해주세요.':'남은 분량은 이미 보충 일정에 배정됐거나 완료됐어요.');return;}
  const preferences=plan.preferences,saved=preferences?.weekOverrides;
  const cells=new Set(saved?.base || []);
  if(!saved?.base){
    const windows=preferences?.windows || Array.from({length:7},(_,day)=>({day,start:day===0||day===6?'10:00':'19:00',end:day===0||day===6?'12:00':'21:00'}));
    windows.forEach(window=>{for(let unit=Math.floor(minutesOfDay(window.start)/30);unit<Math.ceil(minutesOfDay(window.end)/30);unit++)cells.add(`${window.day}:${unit}`);});
  }
  const copy=map=>Object.fromEntries(Object.entries(map || {}).map(([week,units])=>[week,new Set(units)]));
  planCreation={reschedulePlanId:id,step:2,timeCells:cells,weekExtras:copy(saved?.extra),weekExcluded:copy(saved?.excluded),preferenceMode:'weekly',preferenceWeek:creationWeekKey(TODAY),startDate:TODAY,examDate:plan.examDate,originalExamDate:plan.examDate,sourceSignature:remainingSourceSignature(id),returnUi:{selectedPlan:id,planStatusFilter,planSchedulesExpanded,planDetailReturnView,calendarView,selectedDate,viewDate:new Date(viewDate),scrollTop:app.querySelector('.detail-scroll')?.scrollTop || 0,listScrollTop:app.querySelector('.plan-schedule-list')?.scrollTop || 0}};
  renderPlanCreation();
}
function renderRemainingPreferences(){
  const draft=planCreation,plan=plans.find(plan=>plan.id===draft.reschedulePlanId),remaining=remainingReplanSources(plan.id);
  const minutes=remaining.reduce((sum,e)=>sum+remainingWorkMinutes(e),0);
  const scroll=app.querySelector('.creation-scroll')?.scrollTop || 0,timeScroll=app.querySelector('.creation-time-scroll')?.scrollTop;
  document.querySelector('.phone').classList.remove('creation-preview-active');
  app.innerHTML=`<section class="plan-creation remaining-preferences"><header class="toolbar"><button type="button" class="icon-btn" data-plan-create-action="cancel" aria-label="${draft.returnUi.diagnosis?'진단 결과로 돌아가기':'계획 상세로 돌아가기'}">${svg('back')}</button><h1>남은 학습 다시 배정하기</h1></header><div class="creation-scroll">${renderPlanCertificateTarget(plan)}${plan.name!==planCertificate(plan).name?`<p class="creation-eyebrow">${escapeHTML(plan.name)}</p>`:''}<h2>남은 학습에 맞춰 다시 짜요</h2>${draft.priorityTopics?.length?`<section class="creation-section"><h3>진단 결과 반영</h3><p>우선 배정할 주제: ${draft.priorityTopics.map(escapeHTML).join(' · ')}</p><p>선택한 주제의 남은 학습을 먼저 배치해요.</p></section>`:''}<div class="diagnosis-summary"><span>다시 배정할 학습 ${remaining.length}개 · 새로 보충할 과거 기록 ${remaining.filter(e=>e.historySourceId).length}개</span><strong>남은 학습량 ${planTimeLabel(minutes)}</strong><p>오늘 이후 미완료 학습과 과거의 못 했어요·일부 했어요를 포함해요. 일부 완료는 남은 비율만 계산합니다. 과거 기록은 유지하고 보충 일정을 새로 만들며, 이미 배정한 보충 분량은 중복 추가하지 않아요.</p></div><section class="creation-section"><h3>새 공부 선호 시간</h3><p>30분 단위로 칠하거나 지워요. 주별 추가·제외도 적용할 수 있어요.</p>${renderCreationTimeGrid()}</section><section class="creation-section"><h3>다시 배정할 기간</h3><label class="creation-date">배정 시작일<input type="date" data-plan-field="startDate" min="${TODAY}" value="${draft.startDate}"></label><label class="creation-date">시험일<input type="date" data-plan-field="examDate" min="${draft.startDate}" value="${draft.examDate}"></label><p>시험 전날까지 선호 시간 중 비어 있는 시간에 배정해요. 시간이 부족하면 설정을 다시 안내해요.</p></section></div><footer><p id="plan-create-error" class="form-error" role="alert"></p>${draft.returnUi.diagnosis?'<button type="button" class="preview-cancel diagnosis-replan-cancel" data-plan-create-action="cancel">취소</button>':''}<button type="button" class="submit" data-plan-create-action="next">변경 예정 일정 미리보기</button><p>마지막 확인 전까지 일정과 선호 시간·시험일은 바뀌지 않아요.</p></footer></section>`;
  app.querySelector('.creation-scroll').scrollTop=scroll;
  app.querySelector('.creation-time-scroll').scrollTop=timeScroll ?? 864;
}
function previewRemainingReplan(){
  const draft=planCreation,plan=plans.find(plan=>plan.id===draft.reschedulePlanId);
  if(!validExamDate(draft.startDate)||!validExamDate(draft.examDate)||draft.startDate<TODAY||draft.startDate>=draft.examDate){planCreationError('배정 시작일은 오늘부터 시험일 전날 사이로 선택해주세요.');return;}
  if((parseDate(draft.examDate)-parseDate(draft.startDate))/86400000>366){planCreationError('배정 기간은 1년 이내로 선택해주세요.');return;}
  if(remainingSourceSignature(plan.id)!==draft.sourceSignature){planCreationError('학습 기록이 변경됐어요. 계획 상세에서 다시 시작해주세요.');return;}
  const priority=e=>draft.priorityTopics?.some(topic=>(e.topic || e.title)===topic)?0:1;
  const sources=remainingReplanSources(plan.id).sort((a,b)=>priority(a)-priority(b)),replaceIds=new Set(sources.filter(e=>!e.historySourceId).map(e=>e.id)),slots=[];
  const busy=events.filter(e=>!replaceIds.has(e.id) && (e.type==='calendar'||plans.find(plan=>plan.id===e.planId)?.active));
  for(let day=parseDate(draft.startDate);dateKey(day)<draft.examDate;day.setDate(day.getDate()+1)){
    const date=dateKey(day);
    for(const window of creationTimeWindows(creationEffectiveCells(creationWeekKey(date))).filter(window=>window.day===day.getDay())){
      let ranges=[[minutesOfDay(window.start),minutesOfDay(window.end)]];
      busy.filter(e=>e.date===date).forEach(event=>{const start=minutesOfDay(event.start),end=minutesOfDay(event.end);ranges=ranges.flatMap(([a,b])=>end<=a||start>=b?[[a,b]]:[[a,Math.min(start,b)],[Math.max(end,a),b]].filter(([a,b])=>a<b));});
      ranges.forEach(([start,end])=>slots.push({date,start,end}));
    }
  }
  const needed=sources.reduce((sum,e)=>sum+remainingWorkMinutes(e),0),capacity=slots.reduce((sum,slot)=>sum+slot.end-slot.start,0);
  if(!needed){planCreationError('다시 배정할 남은 학습이 없어요.');return;}
  if(capacity<needed){planCreationError(`남은 학습량은 ${planTimeLabel(needed)}인데 배정 가능한 선호 시간은 ${planTimeLabel(capacity)}이에요. 선호 시간을 더 칠하거나 시작일을 앞당겨주세요.`);return;}
  const proposed=[],newRecoveryIds=new Set(),version=++remainingReplanSequence;let slotIndex=0;
  sources.forEach(source=>{
    const total=remainingWorkMinutes(source);let left=total,part=0;
    while(left>0){
      while(slots[slotIndex].start>=slots[slotIndex].end)slotIndex++;
      const slot=slots[slotIndex],length=Math.min(left,slot.end-slot.start),start=slot.start;slot.start+=length;left-=length;
      const {historySourceId,...template}=source;
      const id=historySourceId?`${historySourceId}-recovery-${version}-${++part}`:part++?`${source.id}-remaining-${version}-${part}`:source.id;
      if(historySourceId)newRecoveryIds.add(id);
      proposed.push({...template,id,date:slot.date,start:minutesToTime(start),end:minutesToTime(slot.start),workloadMinutes:studyWorkloadMinutes(source)*length/total,recoverySourceId:historySourceId || source.recoverySourceId,rescheduleRootId:source.rescheduleRootId || source.id,dismissedConflict:''});
    }
  });
  clearCreationUndo();draft.preview={plan:{...plan,examDate:draft.examDate},events:proposed,replaceIds,newRecoveryIds,week:creationWeekKey(proposed[0].date),returnView:{calendarView,selectedDate,viewDate:new Date(viewDate)}};
  draft.replanComparison=true;renderPlanCreation();
}
function replanTopicAllocation(draft=planCreation){
  const original=events.filter(e=>e.type==='study' && e.planId===draft.reschedulePlanId);
  const proposed=[...original.filter(e=>!draft.preview.replaceIds.has(e.id)),...draft.preview.events];
  const sumByTopic=items=>items.reduce((totals,e)=>{const topic=e.topic || e.title;totals.set(topic,(totals.get(topic)||0)+studyWorkloadMinutes(e));return totals;},new Map());
  const before=sumByTopic(original),after=sumByTopic(proposed);
  const names=[...new Set([...before.keys(),...after.keys()])];
  return {topics:names.map(name=>({name,before:before.get(name)||0,after:after.get(name)||0})),beforeTotal:[...before.values()].reduce((sum,n)=>sum+n,0),afterTotal:[...after.values()].reduce((sum,n)=>sum+n,0)};
}
function renderReplanComparison(){
  const draft=planCreation,plan=plans.find(item=>item.id===draft.reschedulePlanId),allocation=replanTopicAllocation(draft);
  const scale=Math.max(1,...allocation.topics.flatMap(topic=>[topic.before,topic.after]));
  document.querySelector('.phone').classList.remove('creation-preview-active');
  app.innerHTML=`<section class="plan-creation replan-comparison"><header class="toolbar"><button type="button" class="icon-btn" data-plan-create-action="back" aria-label="선호 시간 설정으로 돌아가기">${svg('back')}</button><h1>주제별 배정 시간 비교</h1></header><div class="creation-scroll">${renderPlanCertificateTarget(plan)}${plan.name!==planCertificate(plan).name?`<p class="creation-eyebrow">${escapeHTML(plan.name)}</p>`:''}<h2>배정 시간이 이렇게 달라져요</h2><p class="creation-description">기존 일정과 새 배정 예정 일정을 주제별로 비교해요. 과거 기록과 완료 일정도 각 합계에 포함돼요.</p><div class="replan-allocation-legend"><span><i class="before"></i>변경 전</span><span><i class="after"></i>변경 후</span></div><div class="replan-allocation-list" role="group" aria-label="학습 주제별 변경 전후 배정 시간">${allocation.topics.map(topic=>{const delta=topic.after-topic.before;return `<section class="replan-allocation-topic" aria-label="${escapeHTML(topic.name)}, 변경 전 ${planTimeLabel(topic.before)}, 변경 후 ${planTimeLabel(topic.after)}"><h3>${escapeHTML(topic.name)}</h3><div class="replan-allocation-line"><span>변경 전</span><div class="replan-allocation-track"><i class="before" style="width:${topic.before/scale*100}%"></i></div><strong>${planTimeLabel(topic.before)}</strong></div><div class="replan-allocation-line"><span>변경 후</span><div class="replan-allocation-track"><i class="after" style="width:${topic.after/scale*100}%"></i></div><strong>${planTimeLabel(topic.after)}</strong></div><p class="replan-allocation-delta ${delta>0?'increase':delta<0?'decrease':''}">${delta===0?'변화 없음':`${delta>0?'+':'−'}${planTimeLabel(Math.abs(delta))}`}</p></section>`;}).join('')}</div><p class="replan-allocation-total">전체 배정 시간 <strong>${planTimeLabel(allocation.beforeTotal)} → ${planTimeLabel(allocation.afterTotal)}</strong></p><p class="replan-allocation-note">학습 기록과 완료분은 유지돼요. 새 일정은 다음 화면에서 날짜와 시간을 직접 조정할 수 있어요.</p></div><footer><button type="button" class="submit" data-plan-create-action="show-replan-preview">달력에서 변경 예정 일정 보기</button><p>마지막 확인 전까지 실제 일정은 바뀌지 않아요.</p></footer></section>`;
}
function finishRemainingReplan(apply){
  const draft=planCreation;if(!draft?.reschedulePlanId || pendingMove || pointerMove || moveClosing)return;
  const plan=plans.find(plan=>plan.id===draft.reschedulePlanId);
  if(apply){
    if(!draft.preview || !plan || plan.examDate!==draft.originalExamDate || draft.preview.plan.examDate!==draft.examDate || remainingSourceSignature(plan.id)!==draft.sourceSignature){toast('계획이나 학습 기록이 변경됐어요. 다시 미리보기를 만들어주세요.');return;}
  }
  return animateDetailNavigation(()=>{
    if(apply){
      plan.examDate=draft.examDate;
      events=[...events.filter(e=>!draft.preview.replaceIds.has(e.id)),...draft.preview.events.map(e=>({...e}))];
      plan.preferences={windows:creationTimeWindows(),weekOverrides:creationSnapshot()};
    }
    const previous=draft.returnUi;endCreationBrush(null,true);clearCreationUndo();planCreation=null;
    selectedPlan=previous.selectedPlan;selectedTopic=null;planStatusFilter=previous.planStatusFilter;planSchedulesExpanded=previous.planSchedulesExpanded;planDetailReturnView=previous.planDetailReturnView;
    calendarView=previous.calendarView;selectedDate=previous.selectedDate;viewDate=previous.viewDate;selectedEvent=null;searchOpen=false;
    if(previous.diagnosis){
      selectedPlan=null;selfDiagnosis=previous.diagnosis.state;
      renderPlanList();renderSelfDiagnosis();app.querySelector('.creation-scroll').scrollTop=previous.diagnosis.scroll;
      toast(apply?'진단 결과에 맞춰 남은 학습을 다시 배정했어요.':'일정 재배정을 취소했어요.');return;
    }
    renderPlanList();renderCalendar();app.querySelector('.detail-scroll').scrollTop=previous.scrollTop;
    app.querySelector('.plan-schedule-list').scrollTop=previous.listScrollTop;
    toast(apply?'남은 학습 일정과 새 선호 시간·시험일을 반영했어요.':'일정 재배정을 취소했어요.');
  },true,true,draft.returnUi.diagnosis?'diagnosis':'slide');
}
const PLAN_CERTIFICATES=[
  {id:'engineer',name:'정보처리기사 필기',category:'IT · 소프트웨어',description:'소프트웨어 설계부터 데이터베이스까지',topics:['소프트웨어 설계','데이터베이스','프로그래밍'],questions:[
    {text:'요구사항을 분석할 때 가장 먼저 확인할 것은 무엇일까요?',choices:['사용자의 목적과 필요한 기능','화면 배경색','서버 제품명'],answer:0},
    {text:'데이터베이스에서 기본 키의 역할은 무엇일까요?',choices:['데이터 압축','각 행을 고유하게 식별','화면 정렬'],answer:1},
    {text:'같은 작업을 여러 번 수행할 때 사용하는 구조는 무엇일까요?',choices:['주석','변수명','반복문'],answer:2}]},
  {id:'sqld',name:'SQL 개발자(SQLD)',category:'IT · 데이터',description:'데이터 모델링과 SQL 활용 역량',topics:['데이터 모델링','SQL 기본','SQL 활용'],questions:[
    {text:'행을 조회하는 SQL 명령은 무엇일까요?',choices:['SELECT','DELETE','DROP'],answer:0},
    {text:'여러 테이블의 관련 데이터를 연결할 때 사용하는 것은 무엇일까요?',choices:['COMMIT','JOIN','TRUNCATE'],answer:1},
    {text:'집계 결과에 조건을 적용하는 절은 무엇일까요?',choices:['ORDER BY','FROM','HAVING'],answer:2}]},
  {id:'computer',name:'컴퓨터활용능력 1급 필기',category:'사무 · 컴퓨터',description:'컴퓨터 일반, 스프레드시트, 데이터베이스',topics:['컴퓨터 일반','스프레드시트','데이터베이스'],questions:[
    {text:'실행 중인 프로그램의 작업 내용을 임시로 저장하는 장치는 무엇일까요?',choices:['RAM','프린터','스피커'],answer:0},
    {text:'스프레드시트에서 합계를 구하는 함수는 무엇일까요?',choices:['LEFT','SUM','LEN'],answer:1},
    {text:'조건에 맞는 데이터만 표시하는 기능은 무엇일까요?',choices:['인쇄','서식 복사','필터'],answer:2}]}
];
async function openPlanCreation(source='sidebar'){
  if(pendingMove || planCalendarPreview || normalConflictDay || planCreation)return;
  const fabReturn=source==='fab'?captureFabCreationReturn():null;
  await closePlansDrawer();
  if(planCreation)return;
  const exam=parseDate(TODAY);exam.setDate(exam.getDate()+30);
  planCreation={step:0,certificate:null,answers:[null,null,null],timeCells:new Set([1,2,3,4,5].flatMap(day=>[`${day}:38`,`${day}:39`])),weekExtras:{},weekExcluded:{},preferenceWeek:creationWeekKey(TODAY),preferenceMode:'weekly',startDate:TODAY,examDate:dateKey(exam)};
  planCreation.fabReturn=fabReturn;
  if(source==='fab')animateDetailNavigation(()=>renderCalendar(),false,true,'fade');
  else renderCalendar();
}
function cancelPlanCreation(){if(planCreation?.reschedulePlanId){finishRemainingReplan(false);return;}const saved=planCreation?.fabReturn;endCreationBrush(null,true);clearCreationUndo();planCreation=null;if(saved){restoreFabCreationReturn(saved);return;}renderCalendar();app.querySelector('[data-action="plans"]')?.focus({preventScroll:true});}
function planCreationError(message){document.querySelector('#plan-create-error').textContent=message;}
const creationClock=minute=>`${pad(Math.floor(minute/60))}:${pad(minute%60)}`;
function creationWeekKey(date){const day=parseDate(date);day.setDate(day.getDate()-day.getDay());return dateKey(day);}
function creationLayer(mode=planCreation.preferenceMode,week=planCreation.preferenceWeek){
  if(mode==='weekly')return planCreation.timeCells;
  const map=mode==='extra'?planCreation.weekExtras:planCreation.weekExcluded;
  return map[week] ||= new Set();
}
function creationEffectiveCells(week=planCreation.preferenceWeek){
  const cells=new Set(planCreation.timeCells),excluded=planCreation.weekExcluded[week] || [],extra=planCreation.weekExtras[week] || [];
  for(const key of excluded)cells.delete(key);
  for(const key of extra)cells.add(key);
  return cells;
}
function creationSnapshot(){
  const copy=map=>Object.fromEntries(Object.entries(map).filter(([,cells])=>cells.size).sort().map(([week,cells])=>[week,[...cells].sort()]));
  return {base:[...planCreation.timeCells].sort(),extra:copy(planCreation.weekExtras),excluded:copy(planCreation.weekExcluded)};
}
function restoreCreationSnapshot(snapshot){
  planCreation.timeCells=new Set(snapshot.base);
  const copy=map=>Object.fromEntries(Object.entries(map).map(([week,cells])=>[week,new Set(cells)]));
  planCreation.weekExtras=copy(snapshot.extra);planCreation.weekExcluded=copy(snapshot.excluded);
}
function clearCreationUndo(){clearTimeout(creationUndoTimer);creationUndoTimer=null;creationUndo=null;}
function offerCreationUndo(before,week=planCreation.preferenceWeek,mode=planCreation.preferenceMode){
  if(JSON.stringify(before)===JSON.stringify(creationSnapshot()))return;
  clearCreationUndo();creationUndo={editor:planCreation,before,week,mode};
  creationUndoTimer=setTimeout(()=>{clearCreationUndo();if(planCreation?.step===2)syncCreationTimeGrid();},6000);
}
function undoCreationStroke(){
  if(!creationUndo || creationUndo.editor!==planCreation || creationBrush)return;
  const {before,week,mode}=creationUndo;restoreCreationSnapshot(before);
  planCreation.preferenceWeek=week;planCreation.preferenceMode=mode;clearCreationUndo();renderPlanCreation();
  app.querySelector('.creation-brush-status').textContent='방금 칠하거나 지운 영역 전체를 되돌렸어요.';
}
function changeCreationWeek(date){
  if(!validExamDate(date))return;
  endCreationBrush(null,true);planCreation.preferenceWeek=creationWeekKey(date);renderPlanCreation();
}
function creationTimeWindows(cells=planCreation.timeCells){
  const windows=[];
  for(let day=0;day<7;day++)for(let unit=0;unit<48;){
    if(!cells.has(`${day}:${unit}`)){unit++;continue;}
    const start=unit;while(unit<48 && cells.has(`${day}:${unit}`))unit++;
    windows.push({day,start:creationClock(start*30),end:creationClock(unit*30)});
  }
  return windows;
}
function creationCellStyle(key){
  const draft=planCreation,base=draft.timeCells.has(key),extra=draft.weekExtras[draft.preferenceWeek]?.has(key),excluded=base && draft.weekExcluded[draft.preferenceWeek]?.has(key);
  return extra?' extra':excluded?' excluded':base?' painted':'';
}
function creationBrushStatus(){return `이번 주 ${creationEffectiveCells().size/2}시간 · ${creationBrush?(creationBrush.erasing?'지우는 중':'칠하는 중'):'왼쪽 시간축을 밀어 스크롤하세요.'}`;}
function renderCreationTimeGrid(){
  const days=['일','월','화','수','목','금','토'],draft=planCreation,week=parseDate(draft.preferenceWeek),end=new Date(week);end.setDate(end.getDate()+6);
  const selected=creationLayer(),help={weekly:'붉은 영역은 매주 반복해요.',extra:'파란 영역은 선택한 주에만 추가돼요.',excluded:'붉은 주간 선호 위에 칠하면 선택한 주에만 제외해요. 비활성 영역을 지우면 복원돼요.'};
  return `<div class="creation-week-nav"><button type="button" data-plan-create-action="prev-week" aria-label="이전 주">‹</button><label>${week.getMonth()+1}월 ${week.getDate()}일 – ${end.getMonth()+1}월 ${end.getDate()}일<input type="date" data-plan-field="preferenceWeek" aria-label="선호 시간을 조절할 주" value="${draft.preferenceWeek}"></label><button type="button" data-plan-create-action="next-week" aria-label="다음 주">›</button></div><div class="creation-preference-modes" role="group" aria-label="선호 시간 종류">${[['weekly','주간 선호'],['extra','이번 주 추가'],['excluded','이번 주 제외']].map(([mode,label])=>`<button type="button" data-creation-mode="${mode}" aria-pressed="${draft.preferenceMode===mode}"><i class="${mode}"></i>${label}</button>`).join('')}</div><p class="creation-mode-help">${help[draft.preferenceMode]}</p><div class="creation-time-grid"><div class="creation-time-head"><span></span>${days.map((day,index)=>{const date=new Date(week);date.setDate(date.getDate()+index);return `<b>${day}<small>${date.getDate()}</small></b>`;}).join('')}</div><div class="creation-time-scroll" aria-label="주간 공부 선호 시간표"><div class="creation-time-canvas"><div class="creation-hours">${Array.from({length:24},(_,hour)=>`<span style="top:${hour*48}px">${pad(hour)}:00</span>`).join('')}</div>${days.map((label,day)=>`<div class="creation-time-column" data-creation-day="${day}">${Array.from({length:48},(_,unit)=>{const key=`${day}:${unit}`;return `<button type="button" class="creation-time-cell${creationCellStyle(key)}" data-creation-cell="${key}" aria-label="${label}요일 ${creationClock(unit*30)}–${creationClock((unit+1)*30)}" aria-pressed="${selected.has(key)}"></button>`;}).join('')}</div>`).join('')}</div></div><p class="creation-brush-status" role="status">${creationBrushStatus()}</p><div class="creation-undo" ${creationUndo?'':'hidden'}><span>선호 시간을 변경했어요</span><button type="button" data-plan-create-action="undo-time">실행 취소</button></div></div>`;
}
function syncCreationTimeGrid(){
  const selected=creationLayer();
  app.querySelectorAll('[data-creation-cell]').forEach(cell=>{
    const key=cell.dataset.creationCell,style=creationCellStyle(key);
    for(const name of ['painted','extra','excluded'])cell.classList.toggle(name,style===` ${name}`);
    cell.setAttribute('aria-pressed',String(selected.has(key)));
  });
  app.querySelector('.creation-brush-status').textContent=creationBrushStatus();
  const undo=app.querySelector('.creation-undo');if(undo)undo.hidden=!creationUndo;
  planCreationError('');
}
function creationPoint(column,y){
  const rect=column.getBoundingClientRect();
  return {day:Number(column.dataset.creationDay),unit:Math.max(0,Math.min(47,Math.floor((y-rect.top)/rect.height*48)))};
}
function beginCreationBrush(event){
  if(planCreation.step!==2 || planCreation.preview || event.button!==0 || creationBrush)return;
  const column=event.target.closest('[data-creation-day]');if(!column)return;
  const point=creationPoint(column,event.clientY);
  creationBrush={pointerId:event.pointerId,before:creationSnapshot(),mode:planCreation.preferenceMode,week:planCreation.preferenceWeek,erasing:creationLayer().has(`${point.day}:${point.unit}`),last:null};
  app.setPointerCapture(event.pointerId);event.preventDefault();paintCreationPath(point);
}
function paintCreationPath(point){
  const brush=creationBrush,previous=brush.last || point;
  const steps=Math.max(1,Math.abs(point.day-previous.day),Math.abs(point.unit-previous.unit));
  for(let i=0;i<=steps;i++){
    const day=Math.round(previous.day+(point.day-previous.day)*i/steps),unit=Math.round(previous.unit+(point.unit-previous.unit)*i/steps),key=`${day}:${unit}`;
    const layer=creationLayer(brush.mode,brush.week);
    if(brush.erasing)layer.delete(key);else if(brush.mode!=='excluded' || planCreation.timeCells.has(key))layer.add(key);
  }
  brush.last=point;syncCreationTimeGrid();
}
function moveCreationBrush(event){
  if(creationBrush.pointerId!==event.pointerId)return;
  event.preventDefault();
  const column=document.elementFromPoint(event.clientX,event.clientY)?.closest('[data-creation-day]');
  if(column)paintCreationPath(creationPoint(column,event.clientY));else creationBrush.last=null;
}
function endCreationBrush(event,cancel=false){
  const brush=creationBrush;if(!brush || (event && brush.pointerId!==event.pointerId))return;
  if(!cancel && event)moveCreationBrush(event);
  if(cancel && planCreation)restoreCreationSnapshot(brush.before);
  else if(planCreation)offerCreationUndo(brush.before,brush.week,brush.mode);
  creationBrush=null;creationBrushClickUntil=Date.now()+400;
  if(app.hasPointerCapture(brush.pointerId))app.releasePointerCapture(brush.pointerId);
  if(planCreation)syncCreationTimeGrid();
}
function renderPlanCreation(){
  hideQuickOverview();
  if(planCreation.replanComparison){renderReplanComparison();return;}
  if(planCreation.preview){renderCalendar();return;}
  if(planCreation.reschedulePlanId){renderRemainingPreferences();return;}
  document.querySelector('.phone').classList.remove('creation-preview-active');
  const draft=planCreation,certificate=PLAN_CERTIFICATES.find(item=>item.id===draft.certificate);
  const titles=['어떤 자격증을 준비하나요?','지금 실력을 확인해볼까요?','나에게 맞는 학습 시간'];
  const descriptions=['목표 자격증을 하나 선택해주세요.','계획 수립을 위한 예시 진단 3문항이에요.','공부할 요일과 시간, 준비 기간을 정해주세요.'];
  let content='';
  if(draft.step===0)content=`<div class="certificate-list">${PLAN_CERTIFICATES.map((item,index)=>`<button type="button" class="certificate-option" data-plan-cert="${item.id}" aria-pressed="${draft.certificate===item.id}"><span class="certificate-symbol" aria-hidden="true">${['⌘','▤','▦'][index]}</span><span><small>${item.category}</small><strong>${item.name}</strong><span>${item.description}</span></span><i aria-hidden="true">${draft.certificate===item.id?'✓':'○'}</i></button>`).join('')}</div><p class="mobile-help">자격증별 진단 결과를 바탕으로 학습 순서를 구성해요.</p>`;
  else if(draft.step===1)content=`<div class="creation-cert-label">${certificate.name}</div>${certificate.questions.map((q,index)=>`<fieldset class="diagnosis-question"><legend><span>0${index+1}</span> ${q.text}</legend>${[...q.choices,'잘 모르겠어요'].map((choice,option)=>`<label class="diagnosis-answer"><input type="radio" name="diagnosis-${index}" data-plan-question="${index}" value="${option}" ${draft.answers[index]===option?'checked':''}>${choice}</label>`).join('')}</fieldset>`).join('')}`;
  else{
    const score=certificate.questions.filter((q,index)=>q.answer===draft.answers[index]).length;
    content=`<div class="diagnosis-summary"><span>진단 결과 · ${score} / 3</span><strong>${score===3?'실전 문제로 실력을 다져요':score===0?'기초부터 차근차근 시작해요':'헷갈리는 개념부터 보완해요'}</strong><p>진단에서 틀린 주제를 먼저 학습하도록 구성해요.</p></div><section class="creation-section"><h3>공부 선호 시간</h3><p>빈칸에서 시작하면 칠하기, 칠해진 칸에서 시작하면 지우기예요. 30분 단위로 칠하거나 지워요. 위에서 편집할 주와 종류를 선택해주세요.</p>${renderCreationTimeGrid()}</section><section class="creation-section"><h3>준비 기간</h3><label class="creation-date">준비 시작일<input type="date" data-plan-field="startDate" min="${TODAY}" value="${draft.startDate}"></label><label class="creation-date">시험일<input type="date" data-plan-field="examDate" min="${draft.startDate}" value="${draft.examDate}"></label><p>시험 전날까지 선택한 요일에 학습 일정을 만들어요.</p></section>`;
  }
  const scroll=draft.renderedStep===draft.step?(app.querySelector('.creation-scroll')?.scrollTop || 0):0;
  draft.renderedStep=draft.step;
  const timeScroll=app.querySelector('.creation-time-scroll')?.scrollTop;
  app.innerHTML=`<section class="plan-creation"><header class="toolbar"><button type="button" class="icon-btn" data-plan-create-action="back" aria-label="${draft.step?'이전 단계':'학습 계획 생성 취소'}">${svg('back')}</button><h1>학습 계획 생성하기</h1><button type="button" class="icon-btn" data-plan-create-action="cancel" aria-label="학습 계획 생성 닫기">×</button></header><ol class="creation-steps" aria-label="계획 생성 단계">${['자격증 선택','진단 평가','시간·날짜 설정'].map((label,index)=>`<li ${draft.step===index?'aria-current="step"':''} class="${draft.step>=index?'reached':''}"><b>${index<draft.step?'✓':index+1}</b>${label}</li>`).join('')}</ol><div class="creation-scroll"><p class="creation-eyebrow">STEP 0${draft.step+1}</p><h2 tabindex="-1">${titles[draft.step]}</h2><p class="creation-description">${descriptions[draft.step]}</p>${content}</div><footer><p id="plan-create-error" class="form-error" role="alert"></p><button type="button" class="submit" data-plan-create-action="next">${draft.step===2?'학습 계획 생성하기':'다음'}</button><p>생성될 일정을 달력에서 먼저 확인할 수 있어요.</p></footer></section>`;
  app.querySelector('.creation-scroll').scrollTop=scroll;
  if(draft.step===2)app.querySelector('.creation-time-scroll').scrollTop=timeScroll ?? 864;
  app.querySelector('.creation-scroll h2')?.focus({preventScroll:true});
}
function handlePlanCreationInput(event){
  const target=event.target;
  if(target.dataset.planField==='preferenceWeek'){changeCreationWeek(target.value);return;}
  if(target.dataset.planQuestion!==undefined)planCreation.answers[Number(target.dataset.planQuestion)]=Number(target.value);
  else if(['startDate','examDate'].includes(target.dataset.planField))planCreation[target.dataset.planField]=target.value;
  planCreationError('');
}
function handlePlanCreationClick(event){
  const button=event.target.closest('button');if(!button)return;
  if(planCreation.replanComparison){
    if(button.dataset.planCreateAction==='show-replan-preview'){planCreation.replanComparison=false;const first=planCreation.preview.events[0];calendarView='week';viewDate=parseDate(first.date);selectedDate=first.date;selectedEvent=null;searchOpen=false;renderCalendar();}
    else if(button.dataset.planCreateAction==='back'){planCreation.replanComparison=false;planCreation.preview=null;renderPlanCreation();}
    return;
  }
  if(planCreation.preview){handleCreationPreviewClick(button);return;}
  if(button.dataset.planCert){
    if(planCreation.step!==0 || !PLAN_CERTIFICATES.some(item=>item.id===button.dataset.planCert))return;
    if(planCreation.certificate!==button.dataset.planCert)planCreation.answers=[null,null,null];
    planCreation.certificate=button.dataset.planCert;
    app.querySelectorAll('[data-plan-cert]').forEach(option=>{
      const selected=option.dataset.planCert===planCreation.certificate;
      option.setAttribute('aria-pressed',String(selected));
      const mark=option.querySelector('i');if(mark)mark.textContent=selected?'✓':'○';
    });
    planCreationError('');
  }else if(button.dataset.creationCell){
    if(event.detail!==0 && Date.now()<creationBrushClickUntil)return;
    const key=button.dataset.creationCell,before=creationSnapshot(),layer=creationLayer();
    if(layer.has(key))layer.delete(key);else if(planCreation.preferenceMode!=='excluded' || planCreation.timeCells.has(key))layer.add(key);
    offerCreationUndo(before);syncCreationTimeGrid();
  }else if(button.dataset.creationMode){
    if(!['weekly','extra','excluded'].includes(button.dataset.creationMode))return;
    endCreationBrush(null,true);planCreation.preferenceMode=button.dataset.creationMode;renderPlanCreation();
  }else if(button.dataset.planCreateAction==='undo-time')undoCreationStroke();
  else if(['prev-week','next-week'].includes(button.dataset.planCreateAction)){
    const date=parseDate(planCreation.preferenceWeek);date.setDate(date.getDate()+(button.dataset.planCreateAction==='next-week'?7:-7));changeCreationWeek(dateKey(date));
  }else if(button.dataset.planCreateAction==='cancel')cancelPlanCreation();
  else if(button.dataset.planCreateAction==='back'){
    endCreationBrush(null,true);if(planCreation.reschedulePlanId || !planCreation.step)cancelPlanCreation();else{planCreation.step--;renderPlanCreation();}
  }else if(button.dataset.planCreateAction==='next')advancePlanCreation();
}
function renderCreationPreferenceBackground(date){
  const week=creationWeekKey(dateKey(date)),day=date.getDay();
  return [['weekly',planCreation.timeCells],['excluded',planCreation.weekExcluded[week] || new Set()],['extra',planCreation.weekExtras[week] || new Set()]].map(([kind,cells])=>{
    const visible=kind==='excluded'?new Set([...cells].filter(key=>planCreation.timeCells.has(key))):cells;
    return creationTimeWindows(visible).filter(window=>window.day===day).map(window=>`<div class="creation-preview-band band-${kind}" data-preference-kind="${kind}" data-time-minute="${minutesOfDay(window.start)}" data-time-duration="${minutesOfDay(window.end)-minutesOfDay(window.start)}" role="img" aria-label="${['일','월','화','수','목','금','토'][day]}요일 ${kind==='weekly'?'주간 선호':kind==='extra'?'이번 주 추가':'이번 주 제외'} ${window.start}–${window.end}" style="top:${minutesOfDay(window.start)*timelineScale}px;height:${(minutesOfDay(window.end)-minutesOfDay(window.start))*timelineScale}px"></div>`).join('');
  }).join('');
}
const replanEmphasisMotions=new Map();
function clearReplanEmphasisMotions(){for(const animation of replanEmphasisMotions.values())animation.cancel();replanEmphasisMotions.clear();}
function applyReplanHighlight(group){
  if(!planCreation?.reschedulePlanId || !planCreation.preview)return;
  planCreation.preview.highlightGroup=group;
  app.querySelectorAll('[data-replan-event-group]').forEach(node=>{
    const selected=node.dataset.replanEventGroup===group;
    const style=getComputedStyle(node),from={boxShadow:style.boxShadow || 'none'};
    replanEmphasisMotions.get(node)?.cancel();replanEmphasisMotions.delete(node);
    if(node.dataset.replanEventGroup==='new')node.classList.add('replan-default-pulse');
    node.classList.toggle('replan-group-highlight',Boolean(group && selected));
    node.classList.toggle('replan-group-muted',Boolean(group && !selected));
    if(!node.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;
    const animation=node.animate([from,{boxShadow:group && selected?'0 0 0 3px #647d9a55':'0 0 0 0px #647d9a00'}],{duration:280,easing:'ease',fill:'both'});
    replanEmphasisMotions.set(node,animation);
    animation.finished.catch(()=>{}).then(()=>{if(replanEmphasisMotions.get(node)!==animation)return;replanEmphasisMotions.delete(node);animation.cancel();});
  });
  app.querySelectorAll('[data-replan-group]').forEach(node=>node.setAttribute('aria-pressed',String(node.dataset.replanGroup===group)));
}
function dismissReplanHighlight(event){
  if(planCreation?.preview?.examSpotlight && !event.target.closest?.('[data-replan-exam]')?.dataset.replanExam)clearReplanExamSpotlight();
  if(planCreation?.preview?.highlightGroup && !event.target.closest?.('[data-replan-group]')?.dataset.replanGroup)applyReplanHighlight(null);
}
function selectReplanGroup(group){
  if(!['original','new'].includes(group) || !planCreation?.reschedulePlanId)return;
  const preview=planCreation.preview,items=group==='original'?replanOriginalEvents():preview.events;
  if(!items.length)return;
  const next=preview.highlightGroup===group?null:group;
  if(!next){applyReplanHighlight(null);return;}
  const currentWeek=creationWeekKey(dateKey(viewDate));
  const candidates=items.filter(e=>creationWeekKey(e.date)===currentWeek);
  const first=[...(candidates.length?candidates:items)].sort((a,b)=>a.date.localeCompare(b.date)||a.start.localeCompare(b.start))[0];
  const changedPage=Boolean(first && !candidates.length);
  if(changedPage){
    dragPageSlide?.finish();const outgoing=captureDragPage(),direction=first.date>dateKey(viewDate)?1:-1;
    preview.highlightGroup=next;viewDate=parseDate(first.date);selectedDate=first.date;renderCalendar();
    const scroller=app.querySelector('.time-scroll');if(scroller)scroller.scrollTop=Math.max(0,(minutesOfDay(first.start)-60)*timelineScale);
    if(outgoing)animateDragPage(outgoing,direction);
  }else{
    applyReplanHighlight(next);
    const scroller=app.querySelector('.time-scroll');
    if(first && scroller){
      const top=Math.max(0,(minutesOfDay(first.start)-60)*timelineScale);
      if(scroller.scrollTo)scroller.scrollTo({top,behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});
      else scroller.scrollTop=top;
    }
  }
  app.querySelector('[data-replan-group="'+group+'"]')?.focus({preventScroll:true});
}
let replanExamSpotlightTimer=null;
const replanExamMotions=new Map();
function clearReplanExamMotions(){for(const animation of replanExamMotions.values())animation.cancel();replanExamMotions.clear();}
function animateReplanExamNodes(nodes,show){
  const targets=new Set();
  nodes.forEach(node=>targets.add(node.classList.contains('time-day-heading')?node.querySelector('b'):node));
  targets.forEach(node=>{
    if(!node)return;
    const style=getComputedStyle(node),current={boxShadow:style.boxShadow || 'none',outlineColor:style.outlineColor || 'transparent'};
    replanExamMotions.get(node)?.cancel();replanExamMotions.delete(node);
    if(!node.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;
    const neutral={boxShadow:'none',outlineColor:'transparent'};
    const target={boxShadow:node.tagName?.toLowerCase()==='b'?'inset 0 0 0 2px #73a282':'0 0 0 3px #6b9b7a22',outlineColor:'#6b9b7a'};
    const animation=node.animate(show?[neutral,target]:[current,neutral],{duration:240,easing:'ease',fill:'both'});
    replanExamMotions.set(node,animation);
    animation.finished.catch(()=>{}).then(()=>{if(replanExamMotions.get(node)!==animation)return;replanExamMotions.delete(node);animation.cancel();});
  });
}
function clearReplanExamSpotlight(){
  clearTimeout(replanExamSpotlightTimer);replanExamSpotlightTimer=null;
  if(planCreation?.preview)planCreation.preview.examSpotlight=null;
  const nodes=[...app.querySelectorAll('.replan-exam-date-highlight'),...app.querySelectorAll('[data-replan-exam][aria-pressed="true"]')];
  animateReplanExamNodes(nodes,false);
  nodes.forEach(node=>node.classList.remove('replan-exam-date-highlight'));
  app.querySelectorAll('[data-replan-exam]').forEach(node=>node.setAttribute('aria-pressed','false'));
}
function locateReplanExam(kind){
  const draft=planCreation,preview=draft?.reschedulePlanId && draft.preview;
  if(!preview || !['before','after'].includes(kind))return;
  const date=kind==='before'?draft.originalExamDate:preview.plan.examDate,selection={kind,date};
  const previous=dateKey(viewDate),changed=creationWeekKey(previous)!==creationWeekKey(date);
  dragPageSlide?.finish();const outgoing=changed?captureDragPage():null,scroll=app.querySelector('.time-scroll')?.scrollTop || 0;
  clearReplanExamSpotlight();
  preview.examSpotlight=selection;calendarView='week';viewDate=parseDate(date);selectedDate=date;
  if(changed)renderCalendar();
  else{
    app.querySelector('[data-open-day="'+date+'"]')?.classList.add('replan-exam-date-highlight');
    app.querySelector('[data-replan-exam-marker="'+kind+'"]')?.classList.add('replan-exam-date-highlight');
    app.querySelectorAll('[data-replan-exam]').forEach(node=>node.setAttribute('aria-pressed',String(node.dataset.replanExam===kind)));
  }
  const scroller=app.querySelector('.time-scroll');if(scroller)scroller.scrollTop=scroll;
  animateReplanExamNodes([...app.querySelectorAll('.replan-exam-date-highlight'),...app.querySelectorAll('[data-replan-exam][aria-pressed="true"]')],true);
  if(outgoing)animateDragPage(outgoing,date>previous?1:-1);
  app.querySelector('[data-replan-exam="'+kind+'"]')?.focus({preventScroll:true});
  replanExamSpotlightTimer=setTimeout(function expireReplanExamSpotlight(){
    if(planCreation?.preview!==preview || preview.examSpotlight!==selection)return;
    clearReplanExamSpotlight();
  },3000);
}
function renderCreationPreviewControls(){
  const preview=planCreation.preview,conflicts=preview.events.filter(hasConflict).length,replanning=Boolean(planCreation.reschedulePlanId);
  const replacementSummary=replanning?`<div class="replan-change-summary"><button type="button" data-replan-group="original" ${preview.replaceIds.size?'':'disabled'} aria-pressed="${preview.highlightGroup==='original'}">대체할 기존 일정 <strong>${preview.replaceIds.size}개</strong></button><button type="button" data-replan-group="new" ${preview.events.length?'':'disabled'} aria-pressed="${preview.highlightGroup==='new'}">새로 배정할 일정 <strong>${preview.events.length}개</strong></button></div><p class="replan-preserve-note">과거 기록과 완료 일정은 그대로 유지됩니다.<br>새 항목 ${preview.events.filter(e=>!preview.replaceIds.has(e.id)).length}개 중 과거 보충 일정은 ${preview.newRecoveryIds?.size || 0}개예요. 마지막 확인에서만 반영돼요.</p>`:'';
  const examChange=replanning && planCreation.originalExamDate!==preview.plan.examDate?`<section class="replan-exam-change" aria-label="시험일 변경 안내"><button type="button" data-replan-exam="before" aria-pressed="${preview.examSpotlight?.kind==='before'}"><small>변경 전 시험일</small><time datetime="${planCreation.originalExamDate}">${planCreation.originalExamDate.replaceAll('-','.')}</time></button><span aria-hidden="true">→</span><button type="button" data-replan-exam="after" aria-pressed="${preview.examSpotlight?.kind==='after'}"><small>변경 후 시험일</small><time datetime="${preview.plan.examDate}">${preview.plan.examDate.replaceAll('-','.')}</time></button></section>`:'';
  return `<section class="creation-preview-controls" aria-label="${replanning?'남은 학습 재배정 미리보기':'학습 일정 생성 미리보기'}">${replanning?renderPlanCertificateTarget(preview.plan):''}<strong>${escapeHTML(preview.plan.name)} · ${replanning?'남은 학습 재배정':'생성 예정'}</strong>${examChange}${replacementSummary}<div class="creation-preview-legend"><span><i class="weekly"></i>주간 선호</span><span><i class="extra"></i>이번 주 추가</span><span><i class="excluded"></i>이번 주 제외</span></div><p>${replanning?'남은 학습 재배정':'총'} ${preview.events.length}개 학습 일정${conflicts?` · 충돌 ${conflicts}개`:''} · 주 보기에서 드래그해 날짜와 시간을 조정해요. 기존 일정은 선택할 수 없어요.</p><div><button type="button" class="preview-cancel" data-plan-create-action="cancel-preview">취소</button><button type="button" class="submit" data-plan-create-action="confirm-preview">확인</button></div></section>`;
}
function renderCreationPreview(){renderCalendar();}
function stageCreationSchedule(id,schedule){
  const event=planCreation.preview.events.find(event=>event.id===id);
  if(event)Object.assign(event,schedule,{dismissedConflict:''});
}
function cancelCreationPreview(){
  if(!planCreation?.preview || pendingMove || pointerMove || moveClosing)return;
  return animateDetailNavigation(()=>{
  const previous=planCreation.preview.returnView;planCreation.preview=null;
  calendarView=previous.calendarView;viewDate=previous.viewDate;selectedDate=previous.selectedDate;renderPlanCreation();
  },true,true);
}
function finishCreationDraftMove(move,apply){
  const preview=planCreation.preview;
  if(apply){
    const event=preview.events.find(event=>event.id===move.id);
    stageCreationSchedule(event.id,{date:move.date,start:move.start,end:move.end});
    selectedDate=move.date;viewDate=parseDate(move.date);
  }
  calendarView='week';renderCreationPreview();
  toast(apply?'변경 예정 일정에만 반영했어요. 마지막 확인을 누르면 저장돼요.':'이동을 취소하고 생성 예정 미리보기로 돌아왔어요.');
}
function handleCreationPreviewClick(button){
  if(pointerMove || pendingMove || moveClosing)return;
  if(button.dataset.replanExam){locateReplanExam(button.dataset.replanExam);return;}
  if(button.dataset.replanGroup){selectReplanGroup(button.dataset.replanGroup);return;}
  const action=button.dataset.planCreateAction;
  if(action==='confirm-preview' && planCreation.reschedulePlanId){finishRemainingReplan(true);return;}
  if(button.dataset.action==='calendar-conflicts'){showCalendarConflicts();return;}
  if(button.dataset.event || button.dataset.openDay)return;
  if(action==='confirm-preview'){
    const {plan,events:created}=planCreation.preview;
    plans.push(plan);events.push(...created);clearCreationUndo();planCreation=null;
    selectedEvent=null;searchOpen=false;selectedDate=created[0].date;viewDate=parseDate(selectedDate);calendarView='week';
    renderPlanList();renderCalendar();toast(`학습 계획과 ${created.length}개의 일정을 만들었어요`);
  }else if(action==='cancel-preview')cancelCreationPreview();
  else if(action==='preview-prev' || action==='preview-next' || button.dataset.action==='prev' || button.dataset.action==='next'){
    const date=parseDate(planCreation.preview.week);date.setDate(date.getDate()+(action==='preview-next' || button.dataset.action==='next'?7:-7));
    const week=dateKey(date);
    if(week<creationWeekKey(planCreation.startDate)||week>creationWeekKey(planCreation.examDate))return;
    planCreation.preview.week=week;viewDate=parseDate(week);selectedDate=week;renderCreationPreview();
  }
}
function advancePlanCreation(){
  const draft=planCreation;
  if(draft.reschedulePlanId){previewRemainingReplan();return;}
  if(draft.step===0){if(!draft.certificate){planCreationError('준비할 자격증을 선택해주세요.');return;}draft.step++;renderPlanCreation();return;}
  if(draft.step===1){if(draft.answers.some(answer=>answer===null)){planCreationError('모든 문항에 답해주세요. 모르겠다면 ‘잘 모르겠어요’를 선택할 수 있어요.');return;}draft.step++;renderPlanCreation();return;}
  const windows=creationTimeWindows();
  if(!windows.length && !Object.values(draft.weekExtras).some(cells=>cells.size)){planCreationError('공부할 시간대를 하나 이상 칠해주세요.');return;}
  if(!validExamDate(draft.startDate)||!validExamDate(draft.examDate)||draft.startDate<TODAY||draft.examDate<=draft.startDate){planCreationError('준비 시작일은 오늘 이후, 시험일은 준비 시작일보다 뒤로 선택해주세요.');return;}
  if((parseDate(draft.examDate)-parseDate(draft.startDate))/86400000>366){planCreationError('준비 기간은 1년 이내로 선택해주세요.');return;}
  const sessions=[];for(let date=parseDate(draft.startDate);dateKey(date)<draft.examDate;date.setDate(date.getDate()+1)){
    creationTimeWindows(creationEffectiveCells(creationWeekKey(dateKey(date)))).filter(window=>window.day===date.getDay()).forEach(window=>sessions.push({date:dateKey(date),start:window.start,end:window.end}));
  }
  if(!sessions.length){planCreationError('준비 기간에 선택한 시간대가 없어요. 시간대나 날짜를 바꿔주세요.');return;}
  const certificate=PLAN_CERTIFICATES.find(item=>item.id===draft.certificate),id=`created-plan-${++createdPlanSequence}`;
  const score=certificate.questions.filter((q,index)=>q.answer===draft.answers[index]).length;
  const topics=certificate.topics.map((topic,index)=>({topic,correct:certificate.questions[index].answer===draft.answers[index]})).sort((a,b)=>Number(a.correct)-Number(b.correct));
  const plan={id,certificateId:certificate.id,name:certificate.name,examLabel:certificate.id==='computer'?'컴활 1급 시험':certificate.id==='sqld'?'SQLD 시험':'기사 시험일',subtitle:certificate.category,examDate:draft.examDate,active:true,color:'#2b8a57',startDate:draft.startDate,preferences:{windows:windows.map(window=>({...window})),weekOverrides:creationSnapshot()},diagnosis:{score,total:3,answers:[...draft.answers]}};
  const created=sessions.map((session,index)=>({id:`${id}-study-${index}`,planId:id,type:'study',...session,topic:topics[index%topics.length].topic,title:`${topics[index%topics.length].topic} ${score===3?'문제 풀이':'학습'}`,content:`${certificate.name}\n${topics[index%topics.length].topic}\n${score===3?'실전 문제를 풀고 오답을 정리해요.':'핵심 개념을 익히고 연습 문제를 풀어요.'}`,status:'unknown',progress:0}));
  clearCreationUndo();draft.preview={plan,events:created,week:creationWeekKey(sessions[0].date),returnView:{calendarView,viewDate:new Date(viewDate),selectedDate}};
  searchOpen=false;selectedEvent=null;calendarView='week';viewDate=parseDate(sessions[0].date);selectedDate=sessions[0].date;renderPlanCreation();
}
document.querySelector('#create-learning-plan').addEventListener('click',openPlanCreation);

function openTodaySchedules(){
  const today=events.filter(e=>e.date===TODAY && isEventVisible(e)).sort((a,b)=>a.start.localeCompare(b.start));
  document.querySelector('#today-schedules-date').textContent=longDate(TODAY);
  document.querySelector('#today-schedules-list').innerHTML=activeExamsOn(TODAY).map(plan=>`<section class="today-schedule-card"><strong>${escapeHTML(plan.name)} · 시험일</strong></section>`).join('')+today.map(e=>{
    const study=e.type==='study',progress=progressForEvent(e),state=STATUS[e.status] || STATUS.unknown;
    return `<section class="today-schedule-card" data-today-event="${escapeHTML(e.id)}"><button type="button" class="today-event-open" aria-label="${escapeHTML(e.title)} 상세 보기"><strong>${escapeHTML(e.title)}</strong><small>${e.start}–${e.end}${study?' · '+escapeHTML(planFor(e)?.name || ''):' · 기존 캘린더 일정'}</small></button>${study?`<span class="today-schedule-status" style="color:${state.color}">${state.label}</span><div class="today-progress-controls"><input type="range" min="0" max="100" step="1" value="${progress}" data-today-progress="${escapeHTML(e.id)}" aria-label="${escapeHTML(e.title)} 진행률"><label><input type="number" min="0" max="100" step="1" inputmode="numeric" value="${progress}" data-today-progress="${escapeHTML(e.id)}" aria-label="${escapeHTML(e.title)} 진행률 직접 입력"><span>%</span></label></div>`:''}</section>`;
  }).join('')+(!today.length && !activeExamsOn(TODAY).length?'<p class="empty">오늘 배정된 일정이 없어요.</p>':'');
  showPhoneDialog('#today-schedules');
}
async function openTodayEvent(id){
  if(!events.some(e=>e.id===id && e.date===TODAY && isEventVisible(e)))return;
  const scrollTop=document.querySelector('#today-schedules').scrollTop;
  await closePhoneDialog('#today-schedules');
  openEvent(id);
  if(eventDetailReturn?.id===id){eventDetailReturn.todayPopup=true;eventDetailReturn.todayPopupScrollTop=scrollTop;}
}
document.querySelector('#today-schedules-list').addEventListener('click',event=>{
  if(event.target.closest('.today-progress-controls'))return;
  const row=event.target.closest('[data-today-event]');
  if(row)openTodayEvent(row.dataset.todayEvent);
});
document.querySelector('#today-schedules-list').addEventListener('input',event=>{
  const control=event.target,id=control.dataset.todayProgress;
  const study=events.find(e=>e.id===id && e.type==='study' && e.date===TODAY && isEventVisible(e));
  if(!study || !Number.isFinite(control.valueAsNumber))return;
  study.progress=normalizedProgress(control.valueAsNumber);
  study.status=study.progress===100?'done':study.progress===0?'missed':'partial';
  const row=control.closest('[data-today-event]');
  row.querySelectorAll('[data-today-progress]').forEach(input=>{if(input!==control)input.value=study.progress;});
  const status=row.querySelector('.today-schedule-status');status.textContent=STATUS[study.status].label;status.style.color=STATUS[study.status].color;
  renderCalendar();renderPlanList();
});
document.querySelector('#today-schedules-list').addEventListener('focusout',event=>{
  const control=event.target;if(!control.dataset.todayProgress)return;
  const study=events.find(e=>e.id===control.dataset.todayProgress);if(study)control.value=progressForEvent(study);
});
document.querySelector('#mobile-legend').innerHTML = document.querySelector('.guide .legend').innerHTML + '<p class="mobile-help">기준일: 2026.11.25<br>24일까지의 학습 기록에는 어두운 필터가 적용됩니다.<br>디자인 미리보기 · 새로고침하면 초기 상태로 돌아갑니다.</p>';
renderCalendar();
setTimeout(openTodaySchedules,0);


if(typeof window.setInterval==='function')window.setInterval(refreshPlannerDay,60000);
window.addEventListener('focus',refreshPlannerDay);
