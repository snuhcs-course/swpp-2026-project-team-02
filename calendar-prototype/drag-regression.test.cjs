const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

test('selecting a diagnosis plan animates into the quiz from the top and saves the list scroll',async()=>{
  const r=runtime();r.run('openSelfDiagnosis()');r.node('.creation-scroll').scrollTop=133;
  const motions=[],app=r.node('#app'),ghost=r.node('diagnosis-quiz-entry-ghost');
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  app.animate=animate;ghost.animate=animate;ghost.querySelector=()=>null;app.cloneNode=()=>ghost;
  app.getBoundingClientRect=()=>({left:0,top:52,width:412,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  r.run('window.matchMedia=()=>({matches:false})');creationClick(r,{diagnosePlan:'sqld'});
  assert.equal(motions.length,2);
  assert.equal(motions[1].options.duration,340);
  assert.equal(motions[1].frames[0].opacity,0);
  assert.equal(motions[1].frames[0].transform,'translateY(16px)');
  assert.equal(r.node('.creation-scroll').scrollTop,0);
  assert.equal(r.run('selfDiagnosis.selectionScroll'),133);
  assert.match(r.node('#app').innerHTML,/행을 조회하는 SQL/);
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('detailNavigationMotion'),null);
});

test('other-plan diagnosis returns with a fade transition and restores selection scroll before reveal',async()=>{
  const r=runtime();r.run('openSelfDiagnosis()');r.node('.creation-scroll').scrollTop=157;
  creationClick(r,{diagnosePlan:'engineer'});r.run('selfDiagnosis.answers=[0,1,2]');creationClick(r,{diagnosisAction:'submit'});
  r.node('.creation-scroll').scrollTop=390;
  const motions=[],app=r.node('#app'),ghost=r.node('diagnosis-list-return-ghost');
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation,scrollTop:r.node('.creation-scroll').scrollTop});return animation;};
  app.animate=animate;ghost.animate=animate;ghost.querySelector=()=>null;app.cloneNode=()=>ghost;
  app.getBoundingClientRect=()=>({left:0,top:52,width:412,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  r.run('window.matchMedia=()=>({matches:false})');creationClick(r,{diagnosisAction:'more'});
  assert.equal(motions.length,2);
  assert.equal(motions[1].frames[0].opacity,0);
  assert.equal(motions[1].options.duration,340);
  assert.equal(motions[1].scrollTop,157);
  assert.equal(r.run('selfDiagnosis.planId'),null);
  assert.match(r.node('#app').innerHTML,/어떤 계획을 진단할까요/);
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.node('.creation-scroll').scrollTop,157);
  assert.equal(r.run('detailNavigationMotion'),null);
});

test('completed diagnosis card animates its old position and alert colors into the completed list position',async()=>{
  const r=runtime();r.run('openSelfDiagnosis()');
  let completed=false;const motions=[];
  const engineer={dataset:{diagnosePlan:'engineer'},getBoundingClientRect:()=>({top:completed?120:0})};
  const sqld={dataset:{diagnosePlan:'sqld'},getBoundingClientRect:()=>({top:completed?0:120})};
  for(const card of [engineer,sqld])card.animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({card,frames,options,animation});return animation;};
  r.node('#app').querySelectorAll=selector=>selector==='[data-diagnose-plan]'?[engineer,sqld]:[];
  creationClick(r,{diagnosePlan:'engineer'});r.run('selfDiagnosis.answers=[0,1,2]');creationClick(r,{diagnosisAction:'submit'});
  completed=true;r.run('window.matchMedia=()=>({matches:false})');creationClick(r,{diagnosisAction:'more'});
  const motion=motions.find(item=>item.card===engineer);
  assert.equal(motion.frames[0].transform,'translateY(-120px)');
  assert.equal(motion.frames[0].backgroundColor,'#fff0ed');
  assert.equal(motion.frames[1].transform,'translateY(0)');
  assert.equal(motion.options.duration,360);
  motions.forEach(item=>item.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('detailListMotions.size'),0);
});

test('finishing diagnosis clears plan emphasis, moves it below completed plans and clears the home reminder when all are done',()=>{
  const r=runtime();r.run('openSelfDiagnosis()');
  assert.ok(r.node('#app').innerHTML.indexOf('data-diagnose-plan="engineer"')<r.node('#app').innerHTML.indexOf('data-diagnose-plan="sqld"'));
  creationClick(r,{diagnosePlan:'engineer'});r.run('selfDiagnosis.answers=[0,1,2]');creationClick(r,{diagnosisAction:'submit'});
  assert.match(r.node('#app').innerHTML,/다른 계획 진단하기/);
  creationClick(r,{diagnosisAction:'more'});
  const html=r.node('#app').innerHTML;
  assert.match(html,/class="certificate-option diagnosis-complete" data-diagnose-plan="engineer"/);
  assert.ok(html.indexOf('data-diagnose-plan="sqld"')<html.indexOf('data-diagnose-plan="engineer"'));
  creationClick(r,{diagnosisAction:'back'});
  assert.doesNotMatch(r.run('quickOverviewButton()'),/diagnosis-needed/);
});

test('diagnosis selection puts today-pending plans first and shows diagnosis age or no-history guidance',()=>{
  const r=runtime();r.run("plans.reverse();plans.push({id:'no-history',certificateId:'sqld',name:'신규 계획',active:true,startDate:'2026-11-20',examDate:'2026-12-03'})");
  const order=r.run('JSON.stringify(plans.map(p=>p.id))');r.run('openSelfDiagnosis()');
  const html=r.node('#app').innerHTML;
  assert.ok(html.indexOf('data-diagnose-plan="engineer"')<html.indexOf('data-diagnose-plan="sqld"'));
  assert.ok(html.indexOf('data-diagnose-plan="no-history"')<html.indexOf('data-diagnose-plan="sqld"'));
  assert.match(html,/class="certificate-option diagnosis-pending" data-diagnose-plan="engineer"/);
  assert.match(html,/마지막 진단 1일 전/);
  assert.match(html,/진단 기록 없음 · 준비 시작 후 5일/);
  assert.match(html,/오늘 진단 완료/);
  assert.equal(r.run('JSON.stringify(plans.map(p=>p.id))'),order);
});

test('day rollover refreshes diagnosis reminders and scheduled activation while the app stays open',()=>{
  const r=runtime();
  r.run("plans.push({id:'tomorrow',name:'내일 시작',active:true,startDate:'2026-11-26',examDate:'2026-11-28'});syncPlanActivity()");
  assert.equal(r.run('plans.at(-1).active'),false);
  r.run("globalThis.nextWallDay=(()=>{const day=parseDate(lastPlannerWallDay);day.setDate(day.getDate()+1);return dateKey(day);})();plannerWallDay=()=>nextWallDay;refreshPlannerDay()");
  assert.equal(r.run('TODAY'),'2026-11-26');
  assert.equal(r.run('plans.at(-1).active'),true);
  assert.equal(r.run("hasTodayDiagnosis(plans.find(p=>p.id==='sqld'))"),false);
  assert.match(r.node('#app').innerHTML,/<span class="today-icon">26<\/span>/);
});

test('today diagnosis reminder covers only active plans and clears when all have completed today',()=>{
  const r=runtime();
  assert.match(r.run('quickOverviewButton()'),/diagnosis-needed/);
  r.run('openSelfDiagnosis()');
  assert.match(r.node('#app').innerHTML,/오늘 진단 안 함/);assert.match(r.node('#app').innerHTML,/오늘 진단 완료/);
  creationClick(r,{diagnosePlan:'engineer'});r.run('selfDiagnosis.answers=[0,1,2]');creationClick(r,{diagnosisAction:'submit'});creationClick(r,{diagnosisAction:'back'});
  assert.doesNotMatch(r.run('quickOverviewButton()'),/diagnosis-needed/);
  r.run("plans.find(p=>p.id==='engineer').diagnosisHistory.at(-1).date='2026-11-24';renderCalendar()");
  assert.match(r.run('quickOverviewButton()'),/diagnosis-needed/);
  r.run("setPlanActive('engineer',false)");assert.doesNotMatch(r.run('quickOverviewButton()'),/diagnosis-needed/);
});

test('plans automatically activate on preparation start and deactivate after the exam with manual off retained',()=>{
  const r=runtime();
  r.run("plans.push({id:'dated',name:'기간 계획',active:true,startDate:'2026-11-27',examDate:'2026-11-30'});syncPlanActivity()");
  assert.equal(r.run("plans.at(-1).active"),false);
  r.run("syncPlanActivity('2026-11-27')");assert.equal(r.run('plans.at(-1).active'),true);
  r.run("syncPlanActivity('2026-11-30')");assert.equal(r.run('plans.at(-1).active'),true);
  r.run("syncPlanActivity('2026-12-01')");assert.equal(r.run('plans.at(-1).active'),false);
  r.run("syncPlanActivity('2026-11-27');plans.at(-1).active=false;syncPlanActivity('2026-11-28')");
  assert.equal(r.run('plans.at(-1).active'),false);
});

test('all-records navigation fades and slides in opposite directions with scroll restored before reveal',async()=>{
  const r=runtime();await r.run("openPlanDetail('engineer')");r.node('.detail-scroll').scrollTop=207;
  const motions=[],app=r.node('#app'),ghost=r.node('records-transition-ghost');
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  app.animate=animate;ghost.animate=animate;ghost.querySelector=()=>null;app.cloneNode=()=>ghost;
  app.getBoundingClientRect=()=>({left:0,top:52,width:412,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  r.run('window.matchMedia=()=>({matches:false})');creationClick(r,{action:'diagnosis-records-details'});
  assert.equal(motions[1].options.duration,320);
  assert.equal(motions[1].frames[0].opacity,0);
  assert.equal(motions[1].frames[0].transform,'translateX(20px)');
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  creationClick(r,{action:'diagnosis-records-back'});
  assert.equal(motions[3].frames[0].transform,'translateX(-20px)');
  assert.equal(r.node('.detail-scroll').scrollTop,207);
  motions.slice(2).forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('detailNavigationMotion'),null);
});

test('all diagnosis records opens from the graph and restores nested record and plan scroll positions',async()=>{
  const r=runtime();await r.run("openPlanDetail('engineer')");
  const snapshot=r.run('JSON.stringify([plans,events])');r.node('.detail-scroll').scrollTop=218;
  assert.match(r.node('#app').innerHTML,/data-action="diagnosis-records-details"/);
  creationClick(r,{action:'diagnosis-records-details'});
  assert.match(r.node('#app').innerHTML,/전체 진단 기록/);
  assert.equal((r.node('#app').innerHTML.match(/data-diagnosis-index=/g)||[]).length,5);
  r.node('.detail-scroll').scrollTop=96;
  creationClick(r,{diagnosisIndex:'2'});
  assert.equal(r.run('selfDiagnosis.historyAttempt'),3);
  creationClick(r,{diagnosisAction:'back'});
  assert.match(r.node('#app').innerHTML,/전체 진단 기록/);
  assert.equal(r.node('.detail-scroll').scrollTop,96);
  creationClick(r,{action:'diagnosis-records-back'});
  assert.match(r.node('#app').innerHTML,/학습 계획 상세/);
  assert.equal(r.node('.detail-scroll').scrollTop,218);
  assert.equal(r.run('diagnosisRecordsReturn'),null);
  assert.equal(r.run('JSON.stringify([plans,events])'),snapshot);
});

test('historical diagnosis navigation fades both ways and the graph sits below the plan name',async()=>{
  const r=runtime();await r.run("openPlanDetail('engineer')");
  const html=r.node('#app').innerHTML;
  assert.ok(html.indexOf('<h1>정보처리기사 필기</h1>')<html.indexOf('class="card diagnosis-history"'));
  assert.ok(html.indexOf('class="card diagnosis-history"')<html.indexOf('data-action="replan-remaining"'));
  r.node('.detail-scroll').scrollTop=192;
  const motions=[],app=r.node('#app'),ghost=r.node('history-transition-ghost');
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  app.animate=animate;ghost.animate=animate;ghost.querySelector=()=>null;app.cloneNode=()=>ghost;
  app.getBoundingClientRect=()=>({left:0,top:52,width:412,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  r.run('window.matchMedia=()=>({matches:false})');creationClick(r,{diagnosisIndex:'0'});
  assert.equal(motions[1].options.duration,340);
  assert.equal(motions[1].frames[0].opacity,0);
  assert.equal(motions[1].frames[0].transform,'translateY(16px)');
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  creationClick(r,{diagnosisAction:'back'});
  assert.equal(motions[3].options.duration,340);
  assert.equal(motions[3].frames[0].transform,'translateY(-16px)');
  assert.equal(r.node('.detail-scroll').scrollTop,192);
  motions.slice(2).forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('detailNavigationMotion'),null);
});

test('seed diagnosis graph points open their historical answers and restore the plan scroll without recording a new attempt',async()=>{
  const r=runtime();await r.run("openPlanDetail('engineer')");
  const history=r.run("JSON.stringify(plans.find(p=>p.id==='engineer').diagnosisHistory)");
  assert.equal(JSON.parse(history).length,5);
  assert.match(r.node('#app').innerHTML,/data-diagnosis-index="0" role="button" tabindex="0"/);
  r.node('.detail-scroll').scrollTop=228;
  creationClick(r,{diagnosisIndex:'1'});
  assert.equal(r.run('selfDiagnosis.historyAttempt'),2);
  assert.equal(r.run('selfDiagnosis.result.score'),1);
  assert.match(r.node('#app').innerHTML,/2차 진단 결과/);
  assert.match(r.node('#app').innerHTML,/2026-11-10 · 예시 기록/);
  assert.match(r.node('#app').innerHTML,/선택한 답:/);
  creationClick(r,{diagnosisAction:'back'});
  assert.equal(r.run('selectedPlan'),'engineer');
  assert.equal(r.node('.detail-scroll').scrollTop,228);
  assert.equal(r.run("JSON.stringify(plans.find(p=>p.id==='engineer').diagnosisHistory)"),history);
});

test('plan detail charts recent completed diagnoses in order without duplicate submissions',async()=>{
  const r=runtime();await r.run("openPlanDetail('engineer')");
  r.run("plans.find(p=>p.id==='engineer').diagnosisHistory=[];renderPlanDetail()");
  assert.match(r.node('#app').innerHTML,/최근 자가 진단 결과/);
  assert.match(r.node('#app').innerHTML,/완료하면 결과가 여기에/);
  const eventsBefore=r.run('JSON.stringify(events)');
  for(const answers of [[3,3,3],[0,3,2],[0,1,2]]){
    r.run("openSelfDiagnosis('engineer')");r.run(`selfDiagnosis.answers=${JSON.stringify(answers)}`);
    creationClick(r,{diagnosisAction:'submit'});creationClick(r,{diagnosisAction:'submit'});
    creationClick(r,{diagnosisAction:'back'});
  }
  assert.equal(r.run("plans.find(p=>p.id==='engineer').diagnosisHistory.length"),3);
  assert.match(r.node('#app').innerHTML,/1차 0%, 2차 67%, 3차 100%/);
  assert.match(r.node('#app').innerHTML,/<polyline/);
  assert.equal(r.run('JSON.stringify(events)'),eventsBefore);
  r.run("plans.find(p=>p.id==='engineer').diagnosisHistory=Array.from({length:10},(_,i)=>({score:i%4,total:3}));renderPlanDetail()");
  assert.equal((r.node('#app').innerHTML.match(/class="diagnosis-point-value"/g)||[]).length,7);
  assert.match(r.node('#app').innerHTML,/>4차<\/text>/);
  assert.match(r.node('#app').innerHTML,/>10차<\/text>/);
  assert.doesNotMatch(r.node('#app').innerHTML,/>3차<\/text>/);
});

test('remaining replan asks whether to diagnose and routes each choice without changing schedules',async()=>{
  for(const choice of ['cancel','diagnose','direct']){
    const r=await remainingReplanRuntime(),before=r.run('JSON.stringify(events)');
    creationClick(r,{action:'replan-remaining'});
    assert.equal(r.node('#replan-diagnosis-prompt').open,true);
    assert.equal(r.run('planCreation'),null);
    assert.equal(r.run('selfDiagnosis'),null);
    if(choice==='cancel')await r.node('#cancel-replan-diagnosis').handlers.click();
    else await r.node(choice==='diagnose'?'#replan-with-diagnosis':'#replan-without-diagnosis').handlers.click();
    if(choice==='diagnose')assert.equal(r.run('selfDiagnosis.planId'),'replan');
    else if(choice==='direct')assert.equal(r.run('planCreation.reschedulePlanId'),'replan');
    else {assert.equal(r.run('selfDiagnosis'),null);assert.equal(r.run('planCreation'),null);}
    assert.equal(r.run('JSON.stringify(events)'),before);
    assert.equal(r.node('#replan-diagnosis-prompt').open,false);
  }
});

test('plan detail diagnosis uses smooth opposite transitions while preserving the detail scroll',async()=>{
  const r=runtime();await r.run("openPlanDetail('engineer')");r.node('.detail-scroll').scrollTop=263;
  const motions=[],app=r.node('#app'),ghost=r.node('plan-diagnosis-ghost');
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  app.animate=animate;ghost.animate=animate;ghost.querySelector=()=>null;app.cloneNode=()=>ghost;
  app.getBoundingClientRect=()=>({left:0,top:52,width:412,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  r.run('window.matchMedia=()=>({matches:false})');creationClick(r,{action:'diagnose-plan'});
  assert.equal(motions.length,2);
  assert.equal(motions[1].options.duration,320);
  assert.equal(motions[1].frames[0].transform,'translateY(16px)');
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  creationClick(r,{diagnosisAction:'back'});
  assert.equal(motions.length,4);
  assert.equal(motions[3].frames[0].transform,'translateY(-16px)');
  assert.equal(r.node('.detail-scroll').scrollTop,263);
  motions.slice(2).forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('detailNavigationMotion'),null);
  assert.equal(app.classList.contains('diagnosis-navigation'),false);
});

test('plan detail starts its own diagnosis directly and back restores detail scroll',async()=>{
  const r=runtime();await r.run("openPlanDetail('sqld')");
  assert.match(r.node('#app').innerHTML,/data-action="diagnose-plan"/);
  r.node('.detail-scroll').scrollTop=245;
  const before=r.run('JSON.stringify(events)');
  creationClick(r,{action:'diagnose-plan'});
  assert.equal(r.run('selfDiagnosis.planId'),'sqld');
  assert.match(r.node('#app').innerHTML,/행을 조회하는 SQL/);
  assert.doesNotMatch(r.node('#app').innerHTML,/어떤 계획을 진단할까요/);
  creationClick(r,{diagnosisAction:'back'});
  assert.equal(r.run('selfDiagnosis'),null);
  assert.equal(r.run('selectedPlan'),'sqld');
  assert.match(r.node('#app').innerHTML,/학습 계획 상세/);
  assert.equal(r.node('.detail-scroll').scrollTop,245);
  assert.equal(r.run('JSON.stringify(events)'),before);
});

test('choosing a certificate updates only selection without replacing the creation screen',async()=>{
  const r=runtime();await r.run('openPlanCreation()');
  const app=r.node('#app'),before=app.innerHTML,options=['engineer','sqld','computer'].map(id=>({dataset:{planCert:id},mark:{},setAttribute(name,value){this[name]=value;},querySelector(){return this.mark;}}));
  const original=app.querySelectorAll;app.querySelectorAll=selector=>selector==='[data-plan-cert]'?options:original(selector);
  r.node('.creation-scroll').scrollTop=118;
  creationClick(r,{planCert:'sqld'});
  assert.equal(app.innerHTML,before);
  assert.equal(r.node('.creation-scroll').scrollTop,118);
  assert.equal(options[1]['aria-pressed'],'true');
  assert.equal(options[1].mark.textContent,'✓');
  assert.equal(options[0]['aria-pressed'],'false');
  r.run('planCreation.answers=[0,1,2]');creationClick(r,{planCert:'engineer'});
  assert.equal(r.run('JSON.stringify(planCreation.answers)'),'[null,null,null]');
  assert.equal(options[0]['aria-pressed'],'true');
  assert.equal(options[1].mark.textContent,'○');
});

test('diagnosis and remaining replan navigation animates both directions and preserves result scroll',async()=>{
  const r=await remainingReplanRuntime();r.run('closePlanDetail();openSelfDiagnosis()');
  creationClick(r,{diagnosePlan:'replan'});r.run('selfDiagnosis.answers=[3,1,2]');creationClick(r,{diagnosisAction:'submit'});
  r.node('.creation-scroll').scrollTop=184;
  const motions=[],app=r.node('#app'),ghost=r.node('diagnosis-navigation-ghost');
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  app.animate=animate;ghost.animate=animate;ghost.querySelector=()=>null;app.cloneNode=()=>ghost;
  app.getBoundingClientRect=()=>({left:0,top:52,width:412,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  r.run('window.matchMedia=()=>({matches:false})');
  creationClick(r,{diagnosisAction:'replan'});
  assert.equal(motions[1].frames[0].transform,'translateY(16px)');
  assert.equal(motions[1].options.duration,320);
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  creationClick(r,{planCreateAction:'cancel'});
  assert.equal(motions[3].frames[0].transform,'translateY(-16px)');
  assert.equal(r.node('.creation-scroll').scrollTop,184);
  motions.slice(2).forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('detailNavigationMotion'),null);
  assert.equal(app.classList.contains('diagnosis-navigation'),false);
});

for(const exit of ['back','cancel','escape'])test(`diagnosis replanning ${exit} restores the same result and saved scroll`,async()=>{
  const r=await remainingReplanRuntime();r.run('closePlanDetail();openSelfDiagnosis()');
  creationClick(r,{diagnosePlan:'replan'});r.run('selfDiagnosis.answers=[3,1,2]');creationClick(r,{diagnosisAction:'submit'});
  const result=r.run('JSON.stringify(selfDiagnosis)'),records=r.run('JSON.stringify(events)');
  r.node('.creation-scroll').scrollTop=236;creationClick(r,{diagnosisAction:'replan'});
  assert.match(r.node('#app').innerHTML,/aria-label="진단 결과로 돌아가기"/);
  r.node('.creation-scroll').scrollTop=48;
  if(exit==='escape')r.node('#app').handlers.keydown({key:'Escape',target:{closest:()=>null},preventDefault(){}});
  else creationClick(r,{planCreateAction:exit});
  assert.equal(r.run('planCreation'),null);
  assert.equal(r.run('JSON.stringify(selfDiagnosis)'),result);
  assert.equal(r.node('.creation-scroll').scrollTop,236);
  assert.equal(r.run('JSON.stringify(events)'),records);
});

test('weak diagnosis topics lead into remaining replanning and are prioritized only in the draft',async()=>{
  const r=await remainingReplanRuntime();
  r.run("closePlanDetail();events.find(e=>e.id==='rp-partial').topic='데이터베이스';openSelfDiagnosis()");
  creationClick(r,{diagnosePlan:'replan'});
  r.run('selfDiagnosis.answers=[0,3,2]');creationClick(r,{diagnosisAction:'submit'});
  assert.match(r.node('#app').innerHTML,/data-diagnosis-action="replan"/);
  assert.match(r.node('#app').innerHTML,/부족한 주제에 맞춰/);
  const before=r.run('JSON.stringify(events)');r.node('.creation-scroll').scrollTop=145;
  creationClick(r,{diagnosisAction:'replan'});
  assert.equal(r.run('selfDiagnosis'),null);
  assert.equal(r.run('planCreation.reschedulePlanId'),'replan');
  assert.equal(r.run('JSON.stringify(planCreation.priorityTopics)'),JSON.stringify(['데이터베이스']));
  assert.match(r.node('#app').innerHTML,/우선 배정할 주제: 데이터베이스/);
  editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});
  assert.equal(r.run('planCreation.preview.events[0].topic'),'데이터베이스');
  assert.equal(r.run('JSON.stringify(events)'),before);
  r.run('finishRemainingReplan(false)');
  assert.equal(r.run('selfDiagnosis.result.score'),2);
  assert.equal(r.node('.creation-scroll').scrollTop,145);
  assert.equal(r.run('JSON.stringify(events)'),before);
});

test('self diagnosis selects only active plans, scores their certificate quiz and restores the calendar',()=>{
  const r=runtime();r.run("plans.find(p=>p.id==='sqld').active=false;calendarView='week';viewDate=parseDate('2026-11-26');renderCalendar();setFabCreateMenu(true)");
  r.node('.time-scroll').scrollTop=312;
  const studies=r.run('JSON.stringify(events)');
  r.run('openSelfDiagnosis()');
  assert.match(r.node('#app').innerHTML,/data-diagnose-plan="engineer"/);
  assert.doesNotMatch(r.node('#app').innerHTML,/data-diagnose-plan="sqld"/);
  creationClick(r,{diagnosePlan:'engineer'});
  assert.match(r.node('#app').innerHTML,/요구사항/);
  assert.equal(r.run('selfDiagnosis.answers.length'),3);
  creationClick(r,{diagnosisAction:'submit'});assert.equal(r.run('selfDiagnosis.result'),null);
  for(const [index,answer] of [0,1,2].entries())r.node('#app').handlers.input({target:{dataset:{selfQuestion:String(index)},value:String(answer)}});
  creationClick(r,{diagnosisAction:'submit'});
  assert.equal(r.run('selfDiagnosis.result.score'),3);
  assert.equal(r.run("plans.find(p=>p.id==='engineer').latestDiagnosis.score"),3);
  assert.equal(r.run('JSON.stringify(events)'),studies);
  creationClick(r,{diagnosisAction:'back'});
  assert.equal(r.run('selfDiagnosis'),null);
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.node('.time-scroll').scrollTop,312);
  assert.equal(r.run('fabCreateMenuOpen'),true);
});

test('overview toggle works while creation menu stays open and popup dismissal keeps the same controls',async()=>{
  const r=runtime();r.run('setFabCreateMenu(true)');
  r.run("dismissCalendarReturnOnOutside({type:'click',target:{closest:selector=>selector==='[data-action=\"quick-overview\"]'?{}:null}})");
  assert.equal(r.run('fabCreateMenuOpen'),true);
  creationClick(r,{action:'quick-overview'});
  assert.equal(r.run('quickOverviewOpen'),true);
  assert.equal(r.run('fabCreateMenuOpen'),true);
  creationClick(r,{action:'quick-overview'});
  assert.equal(r.run('quickOverviewOpen'),false);
  assert.equal(r.run('fabCreateMenuOpen'),true);
  r.node('#add-form').elements={planId:{},date:{}};
  const page=r.node('#app').innerHTML;
  await r.node('#fab-create-schedule').handlers.click();
  await r.run("closePhoneDialog('#add')");
  assert.equal(r.node('#app').innerHTML,page);
  assert.equal(r.node('.phone').classList.contains('fab-create-open'),true);
  assert.equal(r.run('fabCreateMenuOpen'),true);
});

test('every phone popup locks calendar, creation menu and sheets until it closes',async()=>{
  const r=runtime();r.run('setFabCreateMenu(true)');
  const selectors=JSON.parse(r.run('JSON.stringify(PHONE_DIALOGS)'));
  for(const selector of selectors){
    r.run(`showPhoneDialog(${JSON.stringify(selector)})`);
    for(const surface of ['#app','#fab-create-actions','#quick-overview-sheet','#plans-drawer'])assert.equal(r.node(surface).inert,true,selector+' '+surface);
    r.run("globalThis.scrollBlocked=false;preventPopupBackgroundScroll({target:{closest:()=>null},preventDefault(){globalThis.scrollBlocked=true}})");
    assert.equal(r.run('scrollBlocked'),true);
    await r.run(`closePhoneDialog(${JSON.stringify(selector)})`);
    assert.equal(r.node('#app').inert,false);
    assert.equal(r.node('#fab-create-actions').inert,false);
  }
});

test('clicks while a popup is open never dismiss the expanded creation menu',async()=>{
  const r=runtime();r.run('setFabCreateMenu(true)');
  r.node('#add-form').elements={planId:{},date:{}};
  await r.node('#fab-create-schedule').handlers.click();
  r.run("dismissCalendarReturnOnOutside({type:'click',target:{closest:()=>null}})");
  assert.equal(r.run('fabCreateMenuOpen'),true);
  assert.equal(r.node('.phone').classList.contains('fab-create-open'),true);
  await r.run("closePhoneDialog('#add')");
  assert.equal(r.run('fabCreateMenuOpen'),true);
});

test('entering creation opens the creation screen without icon or menu animations',async()=>{
  const r=runtime(),motions=[];r.run('setFabCreateMenu(true);window.matchMedia=()=>({matches:false})');
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  for(const selector of ['.fab','.quick-overview-button','#fab-create-schedule','#fab-create-plan'])r.node(selector).animate=animate;
  const entering=r.node('#fab-create-plan').handlers.click();
  assert.equal(r.run('planCreation'),null);
  assert.equal(motions.length,0);
  await entering;
  assert.equal(r.run('planCreation.step'),0);
  assert.equal(r.run('fabCreationEntering'),false);
});

test('returning from creation restores the expanded menu without starting animations',async()=>{
  const r=runtime();await r.node('#fab-create-plan').handlers.click();
  const motions=[],app=r.node('#app'),ghost=r.node('creation-return-ghost');
  const clonedPlus=r.node('cloned-plus');
  ghost.querySelectorAll=selector=>selector==='.fab,.quick-overview-button,.self-diagnosis-button'?[clonedPlus]:[];
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  app.animate=animate;ghost.animate=animate;ghost.querySelector=()=>null;app.cloneNode=()=>ghost;
  app.getBoundingClientRect=()=>({left:0,top:52,width:412,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  for(const selector of ['.fab','.quick-overview-button','#fab-create-schedule','#fab-create-plan'])r.node(selector).animate=animate;
  r.run('window.matchMedia=()=>({matches:false});cancelPlanCreation()');
  assert.equal(r.run('fabCreateMenuOpen'),true);
  assert.equal(motions.length,0);
  assert.equal(r.node('.phone').classList.contains('fab-create-open'),true);
  assert.equal(r.node('.phone').classList.contains('fab-menu-restoring'),false);
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('detailNavigationMotion'),null);
});

test('cancelling plan or schedule creation from the plus menu restores the expanded menu and calendar position',async()=>{
  const r=runtime();r.run("calendarView='week';viewDate=parseDate('2026-11-26');renderCalendar();setFabCreateMenu(true)");
  r.node('.time-scroll').scrollTop=321;
  await r.node('#fab-create-plan').handlers.click();
  assert.equal(r.run('planCreation.step'),0);
  r.run('cancelPlanCreation()');
  assert.equal(r.run('fabCreateMenuOpen'),true);
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.node('.time-scroll').scrollTop,321);
  r.node('#add-form').elements={planId:{},date:{}};
  await r.node('#fab-create-schedule').handlers.click();
  assert.equal(r.node('#add').open,true);
  assert.equal(r.run('fabCreateMenuOpen'),true);
  assert.equal(r.node('.phone').classList.contains('fab-create-open'),true);
  await r.run("closePhoneDialog('#add')");
  assert.equal(r.run('fabCreateMenuOpen'),true);
  assert.equal(r.node('.time-scroll').scrollTop,321);
  assert.equal(r.run('fabScheduleReturn'),null);
});

test('outside click closes the creation menu and leaves clicks on its actions alone',()=>{
  const r=runtime();r.run('setFabCreateMenu(true)');
  r.run("dismissCalendarReturnOnOutside({type:'click',target:{closest:selector=>selector==='#fab-create-actions'?{}:null}})");
  assert.equal(r.run('fabCreateMenuOpen'),true);
  r.run("dismissCalendarReturnOnOutside({type:'click',target:{closest:selector=>selector==='[data-action=\"add\"]'?{}:null}})");
  assert.equal(r.run('fabCreateMenuOpen'),true);
  r.run("dismissCalendarReturnOnOutside({type:'click',target:{closest:()=>null},preventDefault(){},stopPropagation(){}})");
  assert.equal(r.run('fabCreateMenuOpen'),false);
  assert.equal(r.node('#fab-create-actions').inert,true);
});

test('calendar plus toggles the two creation actions and closes them on a second press',()=>{
  const r=runtime();creationClick(r,{action:'add'});
  assert.equal(r.run('fabCreateMenuOpen'),true);
  assert.equal(r.node('#fab-create-actions').inert,false);
  assert.equal(r.node('.phone').classList.contains('fab-create-open'),true);
  creationClick(r,{action:'add'});
  assert.equal(r.run('fabCreateMenuOpen'),false);
  assert.equal(r.node('#fab-create-actions').inert,true);
  assert.equal(r.node('.phone').classList.contains('fab-create-open'),false);
});

test('selecting a date from the quick overview switches the existing panel to that date',()=>{
  const r=runtime();r.run('toggleQuickOverview();selectQuickStatus("done")');
  creationClick(r,{date:'2026-11-27'});
  assert.equal(r.run('quickOverviewOpen'),false);
  assert.equal(r.run('selectedDate'),'2026-11-27');
  assert.equal(r.run('monthAgendaExpanded'),true);
  assert.equal(r.run('quickStatusFilter'),null);
  assert.match(r.node('.agenda').innerHTML,/27일 금요일/);
  assert.equal(r.node('.agenda-list').scrollTop,0);
});

test('agenda mode change fades old contents out before showing the other mode',async()=>{
  const r=runtime(),motions=[];
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  for(const selector of ['.agenda-date','.agenda-list','.quick-overview-heading','#quick-overview-graphs'])r.node(selector).animate=animate;
  r.run('window.matchMedia=()=>({matches:false});toggleQuickOverview()');
  assert.equal(r.run('quickOverviewOpen'),false);
  assert.equal(motions.length,2);
  assert.equal(motions[0].frames[1].opacity,0);
  motions.slice(0,2).forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('quickOverviewOpen'),true);
  assert.equal(motions.length,4);
  assert.equal(motions[2].frames[0].opacity,0);
  motions.slice(2).forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('agendaModeMotion'),null);
  r.run('toggleQuickOverview()');
  motions.slice(4,6).forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('quickOverviewOpen'),false);
  motions.slice(6).forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('agendaModeMotion'),null);
});

test('calendar overview switches the existing agenda content and clears highlight on calendar interaction',()=>{
  const r=runtime();r.run('toggleQuickOverview()');
  assert.equal(r.run('monthAgendaExpanded'),true);
  assert.equal(r.node('.agenda').classList.contains('quick-agenda-mode'),true);
  r.run('selectQuickStatus("partial")');
  r.run("clearQuickStatusOnCalendar({target:{closest:selector=>selector==='#app'?{}:null}})");
  assert.equal(r.run('quickStatusFilter'),null);
  assert.equal(r.run('quickOverviewOpen'),true);
  r.run('toggleQuickOverview()');
  assert.equal(r.run('quickOverviewOpen'),false);
  assert.equal(r.run('monthAgendaExpanded'),true);
  assert.equal(r.node('.agenda').classList.contains('quick-agenda-mode'),false);
});

test('quick sheet status selections highlight calendar events without navigating and toggle off',()=>{
  const r=runtime();r.run('quickOverviewOpen=true;syncQuickOverview();selectQuickStatus("done")');
  assert.equal(r.run('activePlansOverview'),null);
  assert.equal(r.run('quickStatusFilter'),'done');
  assert.match(r.run("eventButton(events.find(e=>e.type==='study' && e.status==='done'))"),/quick-status-highlight/);
  assert.doesNotMatch(r.run("eventButton(events.find(e=>e.type==='calendar'))"),/quick-status-highlight/);
  assert.match(r.node('#quick-overview-graphs').innerHTML,/data-topic-status="done" aria-pressed="true"/);
  r.run('selectQuickStatus("done")');assert.equal(r.run('quickStatusFilter'),null);
  assert.doesNotMatch(r.run("eventButton(events.find(e=>e.type==='study' && e.status==='done'))"),/quick-status-highlight/);
});

test('quick graph overview returns to the same calendar with the sheet open and comparison legends',async()=>{
  const r=runtime();r.run("calendarView='week';viewDate=parseDate('2026-11-26');renderCalendar();quickOverviewOpen=true;syncQuickOverview()");
  r.node('.time-scroll').scrollTop=345;
  const graph=r.node('#quick-overview-graphs').innerHTML;
  const comparison=r.run("activePlansStatistics().progress<activePlansStatistics().expectedProgress?'behind':activePlansStatistics().progress>activePlansStatistics().expectedProgress?'ahead':'on-track'");
  assert.ok(graph.includes('sidebar-plan-progress '+comparison));
  assert.match(graph,/plan-progress-reference/);
  assert.doesNotMatch(graph,/class="quick-progress-key"/);
  assert.match(graph,/실제/);assert.match(graph,/예상/);assert.match(graph,/다 했어요/);
  await r.run("openActivePlansOverview('calendar')");
  assert.equal(r.run('quickOverviewOpen'),false);
  assert.equal(r.run('activePlansOverview.source'),'calendar');
  r.run("calendarView='day';viewDate=parseDate('2026-11-29');closeActivePlansOverview()");
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.run('dateKey(viewDate)'),'2026-11-26');
  assert.equal(r.run('quickOverviewOpen'),true);
  assert.equal(r.run('plansDrawerOpen'),false);
  assert.equal(r.node('.time-scroll').scrollTop,345);
});

test('calendar graph sheet toggles and dragging downward dismisses it completely',()=>{
  const r=runtime(),before=r.run('JSON.stringify([plans,events])');
  assert.match(r.node('#app').innerHTML,/data-action="quick-overview"/);
  creationClick(r,{action:'quick-overview'});
  assert.equal(r.run('quickOverviewOpen'),true);
  assert.equal(r.node('#quick-overview-sheet').classList.contains('visible'),true);
  assert.match(r.node('#quick-overview-graphs').innerHTML,/plan-progress-track/);
  assert.match(r.node('#quick-overview-graphs').innerHTML,/topic-status-chart/);
  creationClick(r,{action:'quick-overview'});
  assert.equal(r.run('quickOverviewOpen'),false);
  creationClick(r,{action:'quick-overview'});
  const sheet=r.node('#quick-overview-sheet');
  sheet.handlers.pointerdown({button:0,pointerId:77,clientY:400,currentTarget:sheet});
  sheet.handlers.pointerup({pointerId:77,clientY:480,currentTarget:sheet});
  assert.equal(r.run('quickOverviewOpen'),false);
  assert.equal(sheet.inert,true);
  assert.equal(r.node('.phone').classList.contains('quick-overview-open'),false);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

test('active overview status filters expand schedules and toggle back to all without changing totals',async()=>{
  const r=runtime();await r.run('openActivePlansOverview()');
  const original=r.run('JSON.stringify([plans,events])');
  for(const status of ['done','partial','missed','unknown']){
    r.node('#app').handlers.click({target:{closest:selector=>selector==='[data-topic-status]'?{dataset:{topicStatus:status}}:null}});
    assert.equal(r.run('activePlansOverview.statusFilter'),status);
    assert.equal(r.run('activePlansOverview.expanded'),true);
    const shown=[...r.node('#app').innerHTML.matchAll(/data-event="([^"]+)"/g)].map(match=>match[1]).sort();
    const expected=JSON.parse(r.run(`JSON.stringify(activePlansStatistics().studies.filter(e=>e.status==='${status}').map(e=>e.id).sort())`));
    assert.deepEqual(shown,expected);
    assert.match(r.node('#app').innerHTML,new RegExp(`data-topic-status="${status}" aria-pressed="true"`));
    r.run(`filterActiveSchedules('${status}')`);assert.equal(r.run('activePlansOverview.statusFilter'),null);
    assert.equal([...r.node('#app').innerHTML.matchAll(/data-event=/g)].length,r.run('activePlansStatistics().studies.length'));
  }
  assert.equal(r.run('JSON.stringify([plans,events])'),original);
});

test('active plan overview aggregates only active study schedules and initially collapses its list',async()=>{
  const r=runtime();r.run("plans.find(p=>p.id==='sqld').active=false;openPlansDrawer()");
  r.node('.plans-panel-content').scrollTop=137;
  const before=r.run('JSON.stringify([plans,events])');
  await r.run('openActivePlansOverview()');
  assert.equal(r.run('activePlansOverview.expanded'),false);
  assert.equal(r.run('activePlansStatistics().minutes'),r.run("planStatistics('engineer').minutes"));
  assert.equal(r.run('activePlansStatistics().progress'),r.run("planStatistics('engineer').progress"));
  assert.equal(r.run('activePlansStatistics().expectedProgress'),r.run("planStatistics('engineer').expectedProgress"));
  assert.match(r.node('#app').innerHTML,/전체 일정 상태별 개수/);
  assert.match(r.node('#app').innerHTML,/data-action="toggle-active-schedules" aria-expanded="false"/);
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{action:'toggle-active-schedules'}})}});
  assert.equal(r.run('activePlansOverview.expanded'),true);
  r.run("plans.forEach(p=>p.active=false);renderCalendar()");
  assert.equal(r.run('activePlansStatistics().progress'),0);
  assert.match(r.node('#app').innerHTML,/활성화된 학습 일정이 없어요/);
  assert.doesNotMatch(r.node('#app').innerHTML,/NaN|Infinity/);
  r.run('closeActivePlansOverview()');
  assert.equal(r.run('plansDrawerOpen'),true);
  assert.equal(r.node('.plans-panel-content').scrollTop,137);
});

test('two-finger pinch zooms the timeline smoothly without changing schedules',()=>{
  const r=runtime();r.run("calendarView='week';renderCalendar()");
  const before=r.run('JSON.stringify(events)');
  r.node('.time-scroll').scrollTop=400;r.node('.time-scroll').getBoundingClientRect=()=>({top:100});
  const target={closest:()=>({})},touches=distance=>[{clientX:100,clientY:300},{clientX:100,clientY:300+distance}];
  r.node('#app').handlers.touchstart({target,touches:touches(100),preventDefault(){}});
  r.run('window.matchMedia=()=>({matches:false})');
  r.node('#app').handlers.touchmove({touches:touches(200),preventDefault(){}});
  r.run('animateTimelineZoom()');
  assert.ok(r.run('timelineScale')>1 && r.run('timelineScale')<2);
  for(let i=0;i<30;i++)r.run('animateTimelineZoom()');
  assert.equal(r.run('timelineScale'),2);
  r.node('#app').handlers.touchend({touches:[]});
  assert.equal(r.run('timelineZoomGesture'),null);
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.doesNotMatch(r.node('#app').innerHTML,/data-time-zoom=/);
});

test('timeline vertical zoom keeps the visible time centered and preserves schedule data',()=>{
  const r=runtime();r.run("calendarView='week';renderCalendar()");
  const before=r.run('JSON.stringify(events)');
  r.node('.time-scroll').scrollTop=300;r.node('.time-scroll').clientHeight=400;
  r.run('setTimelineScale(1.5)');
  assert.equal(r.run('timelineScale'),1.5);
  assert.equal(r.node('.time-scroll').scrollTop,550);
  assert.match(r.node('#app').innerHTML,/--time-scale:1.5/);
  assert.match(r.node('#app').innerHTML,/150%/);
  r.run('setTimelineScale(8)');assert.equal(r.run('timelineScale'),2);
  r.run('setTimelineScale(.1)');assert.equal(r.run('timelineScale'),.5);
  assert.equal(r.run('JSON.stringify(events)'),before);
});

test('today schedule opens detail and back reopens the popup with updated results',async()=>{
  const r=runtime();r.run('openTodaySchedules()');
  r.node('#today-schedules').scrollTop=176;
  const id=r.run("events.find(e=>e.date===TODAY && e.type==='study' && isEventVisible(e)).id");
  await r.run(`openTodayEvent(${JSON.stringify(id)})`);
  assert.equal(r.run('selectedEvent'),id);
  assert.equal(r.node('#today-schedules').open,false);
  r.run("setStatus('done');closeEventDetail()");
  assert.equal(r.run('selectedEvent'),null);
  assert.equal(r.node('#today-schedules').open,true);
  assert.equal(r.node('#today-schedules').scrollTop,176);
  assert.match(r.node('#today-schedules-list').innerHTML,/다 했어요/);
  await r.run("closePhoneDialog('#today-schedules')");
  r.run(`openEvent(${JSON.stringify(id)});closeEventDetail()`);
  assert.equal(r.node('#today-schedules').open,false);
});

test('today popup displays visible schedules and updates only today study progress',()=>{
  const r=runtime(),before=r.run('JSON.stringify(events)');
  r.run('openTodaySchedules()');
  assert.equal(r.node('#today-schedules').open,true);
  assert.equal(r.run('JSON.stringify(events)'),before);
  const id=r.run("events.find(e=>e.type==='study' && e.date===TODAY && isEventVisible(e)).id");
  const other=r.run(`JSON.stringify(events.filter(e=>e.id!==${JSON.stringify(id)}))`);
  const mirror={},status={style:{}},row={querySelectorAll:()=>[mirror],querySelector:()=>status};
  const control={dataset:{todayProgress:id},valueAsNumber:45,closest:()=>row};
  for(const [progress,expected] of [[45,'partial'],[100,'done'],[0,'missed']]){
    control.valueAsNumber=progress;r.node('#today-schedules-list').handlers.input({target:control});
    assert.equal(r.run(`events.find(e=>e.id===${JSON.stringify(id)}).status`),expected);
    assert.equal(mirror.value,progress);
  }
  assert.equal(r.run(`JSON.stringify(events.filter(e=>e.id!==${JSON.stringify(id)}))`),other);
});

test('study deletion undo restores the exact schedule and learning record once without overwriting other edits',async()=>{
  const r=runtime();
  r.run("globalThis.deletedStudyId=events.find(e=>e.type==='study' && e.status==='partial').id;openEvent(deletedStudyId)");
  const original=r.run('JSON.stringify(events.find(e=>e.id===deletedStudyId))');
  const index=r.run('events.findIndex(e=>e.id===deletedStudyId)');
  const returnView=r.run('JSON.stringify(eventDetailReturn)');
  r.node('.detail-scroll').scrollTop=287;
  r.node('.study-content').scrollTop=36;
  r.run('openStudyDeletion()');
  await r.node('#confirm-delete-study').handlers.click();
  assert.equal(r.run('events.some(e=>e.id===deletedStudyId)'),false);
  assert.equal(r.node('#study-delete-undo').classList.contains('visible'),true);
  r.run("events.find(e=>e.type==='calendar').title='다른 일정 수정'");
  await r.node('#undo-study-delete').handlers.click();
  assert.equal(r.run('JSON.stringify(events.find(e=>e.id===deletedStudyId))'),original);
  assert.equal(r.run('events.findIndex(e=>e.id===deletedStudyId)'),index);
  assert.equal(r.run('selectedEvent'),r.run('deletedStudyId'));
  assert.equal(r.node('.detail-scroll').scrollTop,287);
  assert.equal(r.node('.study-content').scrollTop,36);
  assert.equal(r.run('JSON.stringify(eventDetailReturn)'),returnView);
  assert.equal(r.run("events.find(e=>e.type==='calendar').title"),'다른 일정 수정');
  r.node('#undo-study-delete').handlers.click();
  assert.equal(r.run('events.filter(e=>e.id===deletedStudyId).length'),1);
  assert.equal(r.node('#study-delete-undo').classList.contains('visible'),false);
});

test('study undo fades the calendar into the restored detail at its saved scroll position',async()=>{
  const r=runtime();r.run("openEvent(events.find(e=>e.type==='study').id)");
  r.node('.detail-scroll').scrollTop=218;r.run('openStudyDeletion()');
  await r.node('#confirm-delete-study').handlers.click();
  const app=r.node('#app'),ghost=r.node('study-restore-ghost'),motions=[];
  ghost.querySelector=()=>null;
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  app.animate=animate;ghost.animate=animate;app.cloneNode=()=>ghost;
  app.getBoundingClientRect=()=>({left:0,top:52,width:412,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  r.run('window.matchMedia=()=>({matches:false})');
  await r.node('#undo-study-delete').handlers.click();
  assert.equal(motions.length,2);
  assert.equal(motions[1].options.duration,360);
  assert.equal(motions[1].frames[0].opacity,0);
  assert.equal(motions[1].frames[0].transform,'translateY(16px)');
  assert.equal(r.node('.detail-scroll').scrollTop,218);
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(ghost.removed,true);assert.equal(r.run('detailNavigationMotion'),null);
});

function swipeCalendar(r,dx,dy=0,cancel=false){
  r.run(`app.handlers.pointerdown({button:0,pointerId:992,clientX:200,clientY:300,target:{closest:selector=>selector==='.grid,.time-scroll'?{}:null}});
    document.handlers.pointermove({pointerId:992,clientX:${200+dx},clientY:${300+dy},preventDefault(){}});
    document.handlers.${cancel?'pointercancel':'pointerup'}({pointerId:992,clientX:${200+dx},clientY:${300+dy},preventDefault(){}});`);
}
function swipeAgenda(r,dx,dy=0,cancel=false){
  r.run(`app.handlers.pointerdown({button:0,pointerId:993,clientX:200,clientY:650,target:{closest:s=>s==='.agenda'?{}:null}});
    document.handlers.pointermove({pointerId:993,clientX:${200+dx},clientY:${650+dy},preventDefault(){}});
    document.handlers.${cancel?'pointercancel':'pointerup'}({pointerId:993,clientX:${200+dx},clientY:${650+dy},preventDefault(){}})`);
}
for(const expanded of [false,true])test(`month agenda swipe changes one day and preserves expanded=${expanded}`,()=>{
  const r=runtime();r.run(`setMonthAgendaExpanded(${expanded})`);
  const before=r.run('JSON.stringify([events,plans])');
  swipeAgenda(r,-100);
  assert.equal(r.run('selectedDate'),'2026-11-26');
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('monthAgendaExpanded'),expanded);
  assert.match(r.node('#app').innerHTML,/26일 목요일/);
  assert.equal(r.run('pointerMove'),null);
  swipeAgenda(r,100);assert.equal(r.run('selectedDate'),'2026-11-25');
  r.run("selectedDate='2026-11-30';viewDate=parseDate(selectedDate);renderCalendar()");
  swipeAgenda(r,-100);assert.equal(r.run('selectedDate'),'2026-12-01');
  assert.equal(r.run('viewDate.getMonth()'),11);
  swipeAgenda(r,100);assert.equal(r.run('selectedDate'),'2026-11-30');
  assert.equal(r.run('viewDate.getMonth()'),10);
  assert.equal(r.run('JSON.stringify([events,plans])'),before);
});
test('month agenda swipes ignore short movements, vertical scrolling and cancellation',()=>{
  const r=runtime();
  for(const [dx,dy,cancel] of [[25,0,false],[10,100,false],[-100,0,true]]){
    swipeAgenda(r,dx,dy,cancel);
    assert.equal(r.run('selectedDate'),'2026-11-25');
    assert.equal(r.run('calendarSwipe'),null);
  }
});
test('month agenda neighbors show adjacent day details and settle back together',async()=>{
  const r=runtime();r.run('window.matchMedia=()=>({matches:false})');
  const surface=r.node('.agenda'),pages=[],motions=[];
  surface.getBoundingClientRect=()=>({left:0,top:600,width:400,height:165});
  r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
  const animate=(frames)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){},finish(){finish();}};motions.push({frames,animation});return animation;};
  surface.animate=animate;
  surface.cloneNode=()=>{const page=r.node(`agenda-neighbor-${pages.length}`);page.animate=animate;pages.push(page);return page;};
  swipeAgenda(r,-30);
  assert.equal(pages.length,2);
  assert.match(pages[0].innerHTML,/24일 화요일/);
  assert.match(pages[1].innerHTML,/26일 목요일/);
  assert.equal(motions.length,3);
  assert.equal(motions[0].frames[0].transform,'translateX(-30px)');
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.created[0].removed,true);
  assert.equal(r.run('selectedDate'),'2026-11-25');
});
for(const mode of ['month','week','day'])test(`${mode} swipe reveals both neighboring calendars and returns all pages together`,async()=>{
  const r=runtime();r.run(`calendarView='${mode}';renderCalendar();window.matchMedia=()=>({matches:false})`);
  const before=r.run('JSON.stringify([events,plans,selectedDate,dateKey(viewDate)])');
  const surface=r.node(mode==='month'?'.grid':'.time-view'),pages=[],motions=[];
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  surface.getBoundingClientRect=()=>({left:10,top:100,width:400,height:450});
  r.node('.phone').getBoundingClientRect=()=>({left:10,top:0});
  surface.cloneNode=()=>{const page=r.node(`neighbor-${pages.length}`);page.animate=animate;const scroll={scrollTop:0};page.querySelector=()=>scroll;pages.push(page);return page;};
  surface.animate=animate;r.node('.time-scroll').scrollTop=620;
  r.run(`app.handlers.pointerdown({button:0,pointerId:992,clientX:200,clientY:300,target:{closest:s=>s==='.grid,.time-scroll'?{}:null}});
    document.handlers.pointermove({pointerId:992,clientX:170,clientY:300,preventDefault(){}})`);
  assert.equal(pages.length,2);
  assert.equal(pages[0].style.transform,'translateX(calc(-100% + -30px))');
  assert.equal(pages[1].style.transform,'translateX(calc(100% + -30px))');
  const overlay=r.created.find(element=>element.className==='drag-page-slide calendar-swipe-neighbors');
  assert.equal(overlay.inert,true);
  if(mode==='month'){
    assert.match(pages[0].innerHTML,/data-date="2026-10-01"/);
    assert.match(pages[1].innerHTML,/data-date="2026-12-31"/);
    assert.match(pages[1].innerHTML,/data-edit-exam="sqld"/);
  }else{
    const markup=r.created.filter(element=>element.innerHTML?.includes('time-view')).map(element=>element.innerHTML).join('');
    assert.match(markup,mode==='week'?/data-open-day="2026-11-15"/:/data-open-day="2026-11-24"/);
    assert.match(markup,mode==='week'?/data-open-day="2026-11-29"/:/data-open-day="2026-11-26"/);
    assert.equal(pages.every(page=>page.querySelector().scrollTop===620),true);
  }
  r.run('document.handlers.pointermove({pointerId:992,clientX:230,clientY:300,preventDefault(){}})');
  assert.equal(pages[0].style.transform,'translateX(calc(-100% + 30px))');
  r.run('document.handlers.pointerup({pointerId:992,clientX:230,clientY:300,preventDefault(){}})');
  assert.equal(motions.length,3);
  assert.equal(motions.every(motion=>motion.options.duration===260),true);
  assert.equal(motions[1].frames[1].transform,'translateX(-100%)');
  assert.equal(motions[2].frames[1].transform,'translateX(100%)');
  motions.forEach(motion=>motion.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(overlay.removed,true);
  assert.equal(surface.style.transform,'');
  assert.equal(r.run('JSON.stringify([events,plans,selectedDate,dateKey(viewDate)])'),before);
});
for(const mode of ['month','week','day'])test(`${mode} calendar follows the pointer and a short swipe settles back without navigating`,async()=>{
  const r=runtime();r.run(`calendarView='${mode}';renderCalendar();window.matchMedia=()=>({matches:false})`);
  const surface=r.node(mode==='month'?'.grid':'.time-view'),animations=[];
  surface.animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){},finish(){finish();}};animations.push({frames,options,animation});return animation;};
  r.run(`app.handlers.pointerdown({button:0,pointerId:992,clientX:200,clientY:300,target:{closest:s=>s==='.grid,.time-scroll'?{}:null}});
    document.handlers.pointermove({pointerId:992,clientX:170,clientY:302,preventDefault(){}})`);
  assert.equal(surface.style.transform,'translateX(-30px)');
  assert.equal(r.run('selectedDate'),'2026-11-25');
  r.run('document.handlers.pointerup({pointerId:992,clientX:170,clientY:302,preventDefault(){}})');
  assert.equal(animations.length,1);
  assert.equal(animations[0].frames[0].transform,'translateX(-30px)');
  assert.equal(animations[0].frames[1].transform,'translateX(0)');
  assert.equal(animations[0].options.duration,260);
  animations[0].animation.finish();await new Promise(resolve=>setImmediate(resolve));
  assert.equal(surface.style.transform,'');
  assert.equal(surface.style.willChange,'');
  assert.equal(r.run('calendarSwipeMotion'),null);
  assert.equal(r.run('selectedDate'),'2026-11-25');
});
for(const [mode,next] of [['month','2026-12-01'],['week','2026-12-02'],['day','2026-11-26']])test(`blank calendar swipe pages the ${mode} view without changing schedules`,()=>{
  const r=runtime(),before=r.run('JSON.stringify(events)');
  r.run(`calendarView='${mode}';renderCalendar()`);
  r.node('.time-scroll').scrollTop=540;
  swipeCalendar(r,-100,5);
  assert.equal(r.run('selectedDate'),next);
  assert.equal(r.run('calendarView'),mode);
  assert.equal(r.run('calendarSwipe'),null);
  if(mode!=='month')assert.equal(r.node('.time-scroll').scrollTop,540);
  swipeCalendar(r,100);
  assert.equal(r.run('selectedDate'),mode==='month'?'2026-11-01':'2026-11-25');
  assert.equal(r.run('JSON.stringify(events)'),before);
});
test('blank calendar gestures ignore vertical scrolling, short swipes, cancellation and taps',()=>{
  const r=runtime();
  for(const [dx,dy,cancel] of [[10,100,false],[35,0,false],[-100,0,true],[0,0,false]]){
    swipeCalendar(r,dx,dy,cancel);
    assert.equal(r.run('selectedDate'),'2026-11-25');
    assert.equal(r.run('calendarSwipe'),null);
  }
});
test('calendar swipes do not start on controls, in previews or behind a popup',()=>{
  const r=runtime();
  assert.equal(r.run("beginCalendarSwipe({button:0,target:{closest:()=>({})}})"),false);
  r.node('.phone').classList.add('popup-interaction-locked');
  swipeCalendar(r,-100);assert.equal(r.run('selectedDate'),'2026-11-25');
  r.node('.phone').classList.remove('popup-interaction-locked');
  r.run('planCreation={preview:{events:[]}}');
  swipeCalendar(r,-100);assert.equal(r.run('selectedDate'),'2026-11-25');
});

function creationClick(r,dataset){r.node('#app').handlers.click({target:{closest:()=>({dataset})}});}
function creationInput(r,field,value){r.node('#app').handlers.input({target:{dataset:{planField:field},value}});}

test('plan creation runs three steps, keeps input when going back and cancels without calendar changes', async () => {
  const r=runtime(),before=r.run('JSON.stringify([plans,events])');
  await r.node('#create-learning-plan').handlers.click();
  assert.match(r.node('#app').innerHTML,/어떤 자격증/);
  creationClick(r,{planCreateAction:'next'});
  assert.match(r.node('#plan-create-error').textContent,/자격증/);
  creationClick(r,{planCert:'sqld'});creationClick(r,{planCreateAction:'next'});
  assert.equal(r.run('planCreation.step'),1);
  creationClick(r,{planCreateAction:'next'});
  assert.match(r.node('#plan-create-error').textContent,/모든 문항/);
  for(let i=0;i<3;i++)r.node('#app').handlers.input({target:{dataset:{planQuestion:String(i)},value:String(i)}});
  creationClick(r,{planCreateAction:'next'});
  assert.equal(r.run('planCreation.step'),2);
  assert.match(r.node('#app').innerHTML,/진단 결과 · 3 \/ 3/);
  r.run("planCreation.timeCells.add('1:36')");
  creationClick(r,{planCreateAction:'back'});creationClick(r,{planCreateAction:'next'});
  assert.equal(r.run("planCreation.timeCells.has('1:36')"),true);
  creationClick(r,{planCreateAction:'cancel'});
  assert.equal(r.run('planCreation'),null);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

for(const certificate of ['engineer','sqld','computer'])test(`creating ${certificate} adds only selected weekday sessions and its exam, with visibility toggles`, async () => {
  const r=runtime(),before=r.run('events.length');
  await r.run('openPlanCreation()');
  creationClick(r,{planCert:certificate});creationClick(r,{planCreateAction:'next'});
  r.run('planCreation.answers=[3,1,3]');creationClick(r,{planCreateAction:'next'});
  creationInput(r,'startDate','2026-11-26');creationInput(r,'examDate','2026-12-03');
  r.run("planCreation.timeCells=new Set(['4:37','4:38','4:39','6:37','6:38','6:39'])");
  creationClick(r,{planCreateAction:'next'});
  assert.ok(r.run('planCreation.preview'));
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run('planCreation'),null);
  assert.equal(r.run('plans.length'),3);
  assert.equal(r.run('events.length'),before+2);
  assert.equal(r.run('plans.at(-1).diagnosis.score'),1);
  assert.equal(r.run('plans.at(-1).certificateId'),certificate);
  assert.ok(r.node('#plans-list').innerHTML.split('data-plan-detail="created-plan-1"')[1].includes(`data-certificate="${certificate}"`));
  assert.equal(r.run('events.at(-2).date'),'2026-11-26');
  assert.equal(r.run('events.at(-1).date'),'2026-11-28');
  assert.equal(r.run('events.at(-1).start'),'18:30');
  assert.equal(r.run('events.at(-1).end'),'20:00');
  assert.equal(r.run("events.slice(-2).every(e=>e.type==='study'&&e.status==='unknown'&&e.progress===0&&validSchedule(e))"),true);
  assert.equal(r.run('events.at(-2).title.startsWith(PLAN_CERTIFICATES.find(c=>c.id===\''+certificate+'\').topics[0])'),true);
  assert.equal(r.run("activeExamsOn('2026-12-03').some(p=>p.id===plans.at(-1).id)"),false);
  assert.equal(r.run('plans.at(-1).active'),false);
  r.run('plans.at(-1).startDate=TODAY;renderCalendar()');
  assert.equal(r.run("activeExamsOn('2026-12-03').some(p=>p.id===plans.at(-1).id)"),true);
  assert.match(r.node('#plans-list').innerHTML,/created-plan-1/);
  r.run('setPlanActive(plans.at(-1).id,false)');
  assert.equal(r.run('isEventVisible(events.at(-1))'),false);
  assert.equal(r.run("activeExamsOn('2026-12-03').some(p=>p.id===plans.at(-1).id)"),false);
});

test('creation rejects empty time selections, invalid dates and periods without a preferred window before any mutation', async () => {
  const r=runtime(),before=r.run('JSON.stringify([plans,events])');await r.run('openPlanCreation()');
  r.run("planCreation.certificate='sqld';planCreation.answers=[0,1,2];planCreation.step=2;planCreation.certificate='sqld';planCreation.timeCells.clear();renderCalendar()");
  creationClick(r,{planCreateAction:'next'});assert.match(r.node('#plan-create-error').textContent,/시간대/);
  r.run("planCreation.timeCells.add('1:38');planCreation.startDate='2026-11-30';planCreation.examDate='2026-11-29'");creationClick(r,{planCreateAction:'next'});assert.match(r.node('#plan-create-error').textContent,/시험일/);
  r.run("planCreation.startDate='2026-11-26';planCreation.examDate='2026-11-27'");creationClick(r,{planCreateAction:'next'});assert.match(r.node('#plan-create-error').textContent,/선택한 시간대가 없어요/);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

function creationStroke(r,day,unit,targetDay,targetUnit,cancel=false){
  const column=r.node('creation-test-column');column.dataset.creationDay=String(day);
  column.getBoundingClientRect=()=>({top:0,height:1152});
  r.node('#app').handlers.pointerdown({button:0,pointerId:501,clientX:100,clientY:unit*24+12,preventDefault(){},target:{closest:selector=>selector==='[data-creation-day]'?column:null}});
  const hit=r.node('creation-test-hit');hit.dataset.creationDay=String(targetDay);hit.getBoundingClientRect=column.getBoundingClientRect;
  r.run("document.elementFromPoint=()=>({closest:()=>document.querySelector('creation-test-hit')})");
  const event={pointerId:501,clientX:150,clientY:targetUnit*24+12,preventDefault(){}};
  r.run(`document.handlers.pointermove({pointerId:501,clientX:150,clientY:${event.clientY},preventDefault(){}})`);
  if(cancel)r.run('document.handlers.pointercancel({pointerId:501})');else r.run(`document.handlers.pointerup({pointerId:501,clientX:150,clientY:${event.clientY},preventDefault(){}})`);
}

test('creation weekly grid paints and erases a continuous path with the operation fixed at pointer down', async () => {
  const r=runtime();await r.run('openPlanCreation()');r.run("planCreation.certificate='sqld';planCreation.step=2;planCreation.timeCells=new Set(['1:20']);renderCalendar()");
  assert.equal((r.node('#app').innerHTML.match(/data-creation-cell=/g)||[]).length,336);
  creationStroke(r,1,18,1,22);
  assert.equal(r.run('planCreation.timeCells.size'),5);
  assert.equal(r.run("[18,19,20,21,22].every(u=>planCreation.timeCells.has('1:'+u))"),true);
  creationStroke(r,1,19,1,25);
  assert.equal(r.run('planCreation.timeCells.size'),1);
  assert.equal(r.run("planCreation.timeCells.has('1:18')"),true);
  assert.equal(r.run('creationBrush'),null);
  assert.equal(r.node('#app').captured,null);
});

test('creation brush follows diagonal days, cancels the whole stroke, and does not double toggle on click', async () => {
  const r=runtime();await r.run('openPlanCreation()');r.run("planCreation.step=2;planCreation.timeCells.clear()");
  creationStroke(r,1,18,3,22);
  const after=r.run('JSON.stringify([...planCreation.timeCells])');
  assert.equal(r.run("planCreation.timeCells.has('1:22')"),false);
  assert.equal(r.run("planCreation.timeCells.has('3:22')"),true);
  creationClick(r,{creationCell:'3:22'});
  assert.equal(r.run('JSON.stringify([...planCreation.timeCells])'),after);
  creationStroke(r,2,10,4,16,true);
  assert.equal(r.run('JSON.stringify([...planCreation.timeCells])'),after);
});

test('separate painted windows generate multiple daily sessions, including a midnight end', async () => {
  const r=runtime();await r.run('openPlanCreation()');
  r.run("planCreation.step=2;planCreation.certificate='sqld';planCreation.answers=[0,1,2];planCreation.startDate='2026-11-26';planCreation.examDate='2026-11-27';planCreation.timeCells.clear()");
  creationStroke(r,4,18,4,19);creationStroke(r,4,46,4,47);
  assert.equal(r.run('creationTimeWindows().length'),2);
  creationClick(r,{planCreateAction:'next'});
  assert.ok(r.run('planCreation.preview'));
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run('planCreation'),null);
  assert.equal(r.run("events.filter(e=>e.planId==='created-plan-1').length"),2);
  assert.equal(r.run('events.at(-1).end'),'24:00');
  assert.equal(r.run('validSchedule(events.at(-1))'),true);
  assert.equal(r.run('events.at(-2).start'),'09:00');
});

for(const mode of ['weekly','extra','excluded'])for(const erase of [false,true])test(`creation ${mode} ${erase?'erase':'paint'} undo restores the entire stroke and the correct week`, async () => {
  const r=runtime();await r.run('openPlanCreation()');
  r.run(`planCreation.step=2;planCreation.certificate='sqld';planCreation.preferenceMode='${mode}';planCreation.weekExtras['2026-12-06']=new Set(['2:18']);renderCalendar()`);
  if(erase && mode!=='weekly')r.run("creationLayer().add('1:38');creationLayer().add('1:39')");
  const before=r.run('JSON.stringify(creationSnapshot())'),saved=r.run('JSON.stringify([plans,events])'),week=r.run('planCreation.preferenceWeek');
  creationStroke(r,1,38,1,42);
  assert.notEqual(r.run('JSON.stringify(creationSnapshot())'),before);
  assert.equal(r.node('.creation-undo').hidden,false);
  creationInput(r,'preferenceWeek','2026-12-06');
  assert.equal(r.run('planCreation.preferenceWeek'),'2026-12-06');
  creationClick(r,{planCreateAction:'undo-time'});
  assert.equal(r.run('JSON.stringify(creationSnapshot())'),before);
  assert.equal(r.run('planCreation.preferenceWeek'),week);
  assert.equal(r.run('planCreation.preferenceMode'),mode);
  assert.equal(r.run('creationUndo'),null);
  assert.equal(r.run('JSON.stringify([plans,events])'),saved);
});

test('week-only preference brushes preserve the recurring base and other weeks and generate their effective schedules', async () => {
  const r=runtime();await r.run('openPlanCreation()');
  r.run("planCreation.step=2;planCreation.certificate='sqld';planCreation.answers=[0,1,2];planCreation.startDate='2026-11-29';planCreation.examDate='2026-12-13';planCreation.timeCells=new Set(['1:38','1:39']);renderCalendar()");
  creationInput(r,'preferenceWeek','2026-11-30');
  assert.equal(r.run('planCreation.preferenceWeek'),'2026-11-29');
  creationClick(r,{creationMode:'excluded'});creationStroke(r,1,38,1,38);
  assert.equal(r.run("creationCellStyle('1:38')"),' excluded');
  creationClick(r,{creationMode:'extra'});creationStroke(r,4,18,4,19);
  assert.equal(r.run("creationCellStyle('4:18')"),' extra');
  const base=r.run('JSON.stringify([...planCreation.timeCells])');
  creationClick(r,{planCreateAction:'next-week'});
  assert.equal(r.run('planCreation.preferenceWeek'),'2026-12-06');
  assert.equal(r.run("creationEffectiveCells().has('1:38')"),true);
  assert.equal(r.run("creationEffectiveCells().has('4:18')"),false);
  assert.equal(r.run('JSON.stringify([...planCreation.timeCells])'),base);
  creationClick(r,{planCreateAction:'next'});
  creationClick(r,{planCreateAction:'confirm-preview'});
  const generated=JSON.parse(r.run("JSON.stringify(events.filter(e=>e.planId==='created-plan-1').map(e=>[e.date,e.start,e.end]))"));
  assert.deepEqual(generated,[['2026-11-30','19:30','20:00'],['2026-12-03','09:00','10:00'],['2026-12-07','19:00','20:00']]);
  assert.equal(r.run("plans.at(-1).preferences.weekOverrides.excluded['2026-11-29'][0]"),'1:38');
});

test('undo keeps the previous completed edit when another stroke is cancelled and clears on exit', async () => {
  const r=runtime();await r.run('openPlanCreation()');
  r.run("planCreation.step=2;planCreation.certificate='sqld';planCreation.timeCells.clear();renderCalendar()");
  const before=r.run('JSON.stringify(creationSnapshot())');
  creationStroke(r,1,18,1,20);
  const edited=r.run('JSON.stringify(creationSnapshot())');
  creationStroke(r,2,18,2,20,true);
  assert.equal(r.run('JSON.stringify(creationSnapshot())'),edited);
  creationClick(r,{planCreateAction:'undo-time'});
  assert.equal(r.run('JSON.stringify(creationSnapshot())'),before);
  creationStroke(r,1,18,1,20);
  creationClick(r,{planCreateAction:'cancel'});
  assert.equal(r.run('creationUndo'),null);
  await r.run('openPlanCreation()');
  assert.equal(r.run('creationUndo'),null);
});

test('week-only additions work without recurring hours and exclusions outside red do not create hours', async () => {
  const r=runtime();await r.run('openPlanCreation()');
  r.run("planCreation.step=2;planCreation.certificate='sqld';planCreation.answers=[0,1,2];planCreation.timeCells.clear();planCreation.startDate='2026-11-26';planCreation.examDate='2026-12-11';renderCalendar()");
  creationClick(r,{creationMode:'excluded'});creationStroke(r,4,18,4,20);
  assert.equal(r.run('creationEffectiveCells().size'),0);
  assert.equal(r.run('creationUndo'),null);
  creationClick(r,{creationMode:'extra'});creationStroke(r,4,18,4,19);
  creationClick(r,{planCreateAction:'next'});
  assert.ok(r.run('planCreation.preview'));
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run('planCreation'),null);
  assert.equal(r.run("events.filter(e=>e.planId==='created-plan-1').length"),1);
  assert.equal(r.run('events.at(-1).date'),'2026-11-26');
});

test('creation previews schedules in a week calendar with all three preference backgrounds before committing', async () => {
  const r=runtime();await r.run('openPlanCreation()');
  r.run("planCreation.step=2;planCreation.certificate='sqld';planCreation.answers=[0,1,2];planCreation.startDate='2026-11-26';planCreation.examDate='2026-12-03';planCreation.timeCells=new Set(['4:28','4:29']);planCreation.weekExcluded['2026-11-22']=new Set(['4:28']);planCreation.weekExtras['2026-11-22']=new Set(['5:20']);renderCalendar()");
  const before=r.run('JSON.stringify([plans,events])'),settings=r.run('JSON.stringify(creationSnapshot())');
  creationClick(r,{planCreateAction:'next'});
  const html=r.node('#app').innerHTML;
  assert.match(html,/calendar-toolbar view-week/);
  assert.doesNotMatch(html,/creation-calendar-preview/);
  assert.match(html,/week-view/);
  assert.match(html,/data-preference-kind="weekly"/);
  assert.match(html,/data-preference-kind="extra"/);
  assert.match(html,/data-preference-kind="excluded"/);
  assert.match(html,/creation-preview-event/);
  assert.doesNotMatch(html,/class="fab"/);
  assert.match(html,/movable-event/);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.run("calendarEvents().filter(e=>e.isCreationPreview).length"),2);
  assert.equal(r.run("hasConflict(calendarEvents().find(e=>e.isCreationPreview && e.date==='2026-11-26'))"),true);
  creationClick(r,{planCreateAction:'preview-next'});
  assert.equal(r.run('planCreation.preview.week'),'2026-11-29');
  assert.doesNotMatch(r.node('#app').innerHTML,/data-preference-kind="extra"|data-preference-kind="excluded"/);
  assert.match(r.node('#app').innerHTML,/SQLD 시험/);
  creationClick(r,{planCreateAction:'preview-next'});
  assert.equal(r.run('planCreation.preview.week'),'2026-11-29');
  creationClick(r,{planCreateAction:'cancel-preview'});
  assert.equal(r.run('planCreation.preview'),null);
  assert.equal(r.run('planCreation.step'),2);
  assert.equal(r.run('JSON.stringify(creationSnapshot())'),settings);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  assert.equal(r.run('calendarEvents().length'),r.run('events.length'));
  creationClick(r,{planCreateAction:'next'});
  const count=r.run('planCreation.preview.events.length');
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run('planCreation'),null);
  assert.equal(r.run('plans.length'),3);
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.run('events.filter(e=>e.planId===plans.at(-1).id).length'),count);
  const committed=r.run('JSON.stringify([plans,events])');
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run('JSON.stringify([plans,events])'),committed);
  assert.doesNotMatch(r.node('#app').innerHTML,/creation-preview-band/);
});

test('Escape from creation preview returns to settings with dates and diagnostic answers intact', async () => {
  const r=runtime();await r.run('openPlanCreation()');
  r.run("planCreation.step=2;planCreation.certificate='sqld';planCreation.answers=[0,1,3];renderCalendar()");
  const before=r.run('JSON.stringify([plans,events])'),date=r.run('planCreation.examDate');
  creationClick(r,{planCreateAction:'next'});
  r.node('#app').handlers.keydown({key:'Escape',preventDefault(){}});
  assert.equal(r.run('planCreation.preview'),null);
  assert.equal(r.run('planCreation.examDate'),date);
  assert.equal(r.run('planCreation.answers[2]'),3);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

async function creationDragPreview(){
  const r=runtime();await r.run('openPlanCreation()');
  r.run("planCreation.step=2;planCreation.certificate='sqld';planCreation.answers=[0,1,2];planCreation.startDate='2026-11-26';planCreation.examDate='2026-12-03';planCreation.timeCells=new Set(['4:38','4:39','5:38','5:39']);renderCalendar();advancePlanCreation()");
  r.node('#move-preview').hidden=true;
  return r;
}

test('creation preview time drag confirms into the draft and commits only on the final plan confirmation', async () => {
  const r=await creationDragPreview(),before=r.run('JSON.stringify([plans,events])');
  const id=r.run('planCreation.preview.events[0].id');
  r.node(`source-${id}`).getBoundingClientRect=()=>({top:100,height:58});
  r.node('day-2026-11-28').getBoundingClientRect=()=>({top:-800,height:1440});
  r.down(id);r.hover('2026-11-28',130);
  assert.equal(r.run('liveMove.start'),'15:30');
  assert.equal(r.node('#move-preview').hidden,true);
  assert.equal(r.node(`[data-event="${id}"]`).classList.contains('plan-preview-drag-source'),true);
  r.up();
  assert.equal(r.node('#move-preview').hidden,false);
  assert.equal(r.node('#move-preview').classList.contains('timeline-preview'),true);
  assert.equal(r.run('pendingMove.start'),'15:30');
  assert.match(r.node('#move-time-comparison').innerHTML,/19:00–20:00/);
  assert.match(r.node('#move-time-comparison').innerHTML,/15:30–16:30/);
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  await r.run('confirmMove()');
  assert.equal(r.run('pendingMove'),null);
  assert.equal(r.run('planCreation.preview.events[0].date'),'2026-11-28');
  assert.equal(r.run('planCreation.preview.events[0].start'),'15:30');
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run(`events.find(e=>e.id==='${id}').date`),'2026-11-28');
  assert.equal(r.run(`events.find(e=>e.id==='${id}').start`),'15:30');
});

test('cancelling a creation preview drag retains previous staged changes and the original plan settings', async () => {
  const r=await creationDragPreview(),before=r.run('JSON.stringify([plans,events])');
  const id=r.run('planCreation.preview.events[0].id');
  for(const [date,cancel] of [['2026-11-28',false],['2026-11-29',true]]){
    r.node(`source-${id}`).getBoundingClientRect=()=>({top:100,height:58});
    r.node(`day-${date}`).getBoundingClientRect=()=>({top:-800,height:1440});
    r.down(id);r.hover(date,130);r.up();
    await r.run(cancel?'cancelMove()':'confirmMove()');
  }
  assert.equal(r.run('planCreation.preview.events[0].date'),'2026-11-28');
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  creationClick(r,{planCreateAction:'cancel-preview'});
  assert.equal(r.run('planCreation.preview'),null);
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('planCreation.startDate'),'2026-11-26');
  creationClick(r,{planCreateAction:'next'});
  assert.equal(r.run('planCreation.preview.events[0].date'),'2026-11-26');
});

test('creation preview touch drag crosses to the next week at an edge and can cancel back to the original week', async () => {
  const r=await creationDragPreview(),before=r.run('JSON.stringify([plans,events])');edgeGeometry(r);
  const id=r.run('planCreation.preview.events[0].id');
  r.node(`source-${id}`).getBoundingClientRect=()=>({top:100,height:58});
  for(const date of ['2026-11-26','2026-12-03'])r.node(`day-${date}`).getBoundingClientRect=()=>({top:-800,height:1440});
  r.down(id,'touch');assert.equal(r.run('pointerMove.active'),false);
  r.hold();r.hoverAt('2026-11-26',400,200);
  assert.equal(r.edgePending(),true);
  r.setHitDate('2026-12-03');r.waitEdge();
  assert.equal(r.run('planCreation.preview.week'),'2026-11-29');
  assert.equal(r.run('pointerMove.id'),id);
  r.up();assert.equal(r.run('pendingMove.date'),'2026-12-03');
  await r.run('cancelMove()');
  assert.equal(r.run('planCreation.preview.week'),'2026-11-22');
  assert.equal(r.run('planCreation.preview.events[0].date'),'2026-11-26');
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

test('creation preview prevents dragging existing events and blocks dates before preparation or after its own exam', async () => {
  const r=await creationDragPreview(),before=r.run('JSON.stringify([plans,events])');
  r.down('sample-19');assert.equal(r.run('pointerMove'),null);
  const id=r.run('planCreation.preview.events[0].id');
  for(const date of ['2026-11-25','2026-12-04']){
    r.node(`source-${id}`).getBoundingClientRect=()=>({top:100,height:58});
    r.node(`day-${date}`).getBoundingClientRect=()=>({top:-800,height:1440});
    r.down(id);r.hover(date,130);r.up();
    assert.equal(r.node('#confirm-move').disabled,true);
    assert.equal(r.run('pendingMove.blockedDate'),date);
    await r.run('confirmMove()');
    assert.equal(r.run('JSON.stringify([plans,events])'),before);
    await r.run('cancelMove()');
  }
  assert.equal(r.run('planCreation.preview.events[0].date'),'2026-11-26');
});

async function creationConflictPreview(){
  const r=runtime();await r.run('openPlanCreation()');
  r.run("planCreation.step=2;planCreation.certificate='sqld';planCreation.answers=[0,1,2];planCreation.startDate='2026-11-26';planCreation.examDate='2026-12-03';planCreation.timeCells=new Set(['4:28','4:29']);renderCalendar();advancePlanCreation()");
  r.node('#move-preview').hidden=true;
  return r;
}

test('conflicting study and calendar event clicks stay in creation week preview without changing schedules', async () => {
  const r=await creationConflictPreview(),before=r.run('JSON.stringify([plans,events])');
  const generated=r.run('planCreation.preview.events[0].id');
  const existing=r.run("events.find(e=>e.type==='calendar' && e.date==='2026-11-26').id");
  const existingMarkup=r.run(`eventButton(movePreviewEvent('${existing}'))`);
  const generatedMarkup=r.run(`eventButton(movePreviewEvent('${generated}'))`);
  assert.match(existingMarkup,/creation-preview-readonly/);
  assert.match(existingMarkup,/disabled aria-disabled="true" tabindex="-1"/);
  assert.match(existingMarkup,/선택 불가/);
  assert.doesNotMatch(generatedMarkup,/creation-preview-readonly|aria-disabled/);
  assert.match(generatedMarkup,/movable-event/);
  for(const id of [generated,existing]){
    creationClick(r,{event:id});
    assert.equal(r.run('calendarView'),'week');
    assert.match(r.node('#app').innerHTML,/calendar-toolbar view-week/);
    assert.match(r.node('#app').innerHTML,/드래그해 날짜와 시간을 조정/);
    assert.doesNotMatch(r.node('#app').innerHTML,/confirm-day|cancel-day|day-view/);
    assert.equal(r.run('JSON.stringify([plans,events])'),before);
  }
  creationClick(r,{openDay:'2026-11-26'});
  assert.equal(r.run('calendarView'),'week');
});

test('event detail opens direct schedule editing, validates date limits and preserves learning results when saved', async () => {
  const r=runtime();r.node('#edit-form').elements={date:{},start:{},end:{}};
  r.run("openEvent('sample-19')");
  assert.match(r.node('#app').innerHTML,/data-action="edit-schedule"/);
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{action:'edit-schedule'}})}});
  assert.equal(r.node('#edit-schedule').open,true);
  assert.equal(r.node('#edit-form').elements.date.value,'2026-11-26');
  const form=r.node('#edit-form'),before=r.run("JSON.stringify(events.find(e=>e.id==='sample-19'))");
  for(const date of ['2026-11-24','2026-12-01']){
    form.elements.date.value=date;form.elements.start.value='17:00';form.elements.end.value='18:00';
    form.handlers.submit({preventDefault(){},currentTarget:form});
    assert.equal(r.run("JSON.stringify(events.find(e=>e.id==='sample-19'))"),before);
    assert.notEqual(r.node('#edit-error').textContent,'');
  }
  form.elements.date.value='2026-11-28';
  form.handlers.submit({preventDefault(){},currentTarget:form});await Promise.resolve();await Promise.resolve();
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-28');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'17:00');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').status"),'unknown');
  assert.equal(r.run('selectedEvent'),'sample-19');
  r.run("openEvent('sample-14')");
  assert.doesNotMatch(r.node('#app').innerHTML,/data-action="edit-schedule"/);
});

test('existing calendar schedules remain read only in detail and reject direct editor submissions', () => {
  const r=runtime();r.node('#edit-form').elements={date:{},start:{},end:{}};
  const id=r.run("events.find(e=>e.type==='calendar' && e.date==='2026-11-26').id");
  const before=r.run(`JSON.stringify(events.find(e=>e.id==='${id}'))`);
  r.run(`openEvent('${id}');openScheduleEditor()`);
  assert.doesNotMatch(r.node('#app').innerHTML,/data-action="edit-schedule"/);
  assert.match(r.node('#app').innerHTML,/기존 캘린더 일정은 날짜와 시간을 수정할 수 없어요/);
  assert.notEqual(r.node('#edit-schedule').open,true);
  const form=r.node('#edit-form');form.elements.date.value='2026-12-05';form.elements.start.value='09:00';form.elements.end.value='10:30';
  form.handlers.submit({preventDefault(){},currentTarget:form});
  assert.equal(r.run(`JSON.stringify(events.find(e=>e.id==='${id}'))`),before);
  assert.equal(r.run(`events.find(e=>e.id==='${id}').type`),'calendar');
  assert.equal(r.run(`canMoveEvent(events.find(e=>e.id==='${id}'))`),false);
});

test('plan details calculate topic hours and progress weighted by duration and ignore calendar and other-plan records', async () => {
  const r=runtime();
  r.run(`plans.push({id:'stats',name:'통계 계획',subtitle:'주제별 학습',examDate:'2026-12-03',active:true});
    events.push(...[
      {id:'s-a',topic:'데이터베이스',title:'개념',start:'09:00',end:'10:00',status:'done',progress:100},
      {id:'s-b',topic:'데이터베이스',title:'기출',start:'10:00',end:'12:00',status:'partial',progress:50},
      {id:'s-c',topic:'알고리즘',title:'알고리즘',start:'13:00',end:'14:00',status:'unknown',progress:0}
    ].map(e=>({...e,planId:'stats',date:'2026-11-26',type:'study',content:'예시'})));
    events.push({id:'not-study',planId:'stats',type:'calendar',date:TODAY,start:'00:00',end:'23:00',status:'done'})`);
  assert.equal(r.run("planStatistics('stats').minutes"),240);
  assert.equal(r.run("planStatistics('stats').completedMinutes"),120);
  assert.equal(r.run("planStatistics('stats').progress"),50);
  assert.equal(r.run("planStatistics('stats').topics[0].progress"),67);
  await r.run("openPlanDetail('stats')");
  assert.match(r.node('#app').innerHTML,/학습 계획 상세/);
  assert.match(r.node('#app').innerHTML,/데이터베이스/);
  assert.match(r.node('#app').innerHTML,/4시간/);
  assert.match(r.node('#app').innerHTML,/전체 학습 진행률 50퍼센트/);
  assert.match(r.node('#app').innerHTML,/plan-status-overview/);
  assert.match(r.node('#app').innerHTML,/전체 일정 상태별 개수/);
  assert.match(r.node('#app').innerHTML,/다 했어요 1개, 일부 했어요 1개, 못 했어요 0개, 미확인 1개/);
  assert.match(r.node('#app').innerHTML,/<svg viewBox="0 0 142 142" role="group"/);
});

test('topic progress compares its own scheduled workload and keeps the baseline stable across state filters',async()=>{
  const r=runtime();
  r.run(`events=[
    {id:'comparison-past',date:TODAY,start:'09:00',end:'10:00',workloadMinutes:120,status:'partial',progress:50},
    {id:'comparison-future',date:'2026-11-26',start:'09:00',end:'11:00',status:'unknown',progress:0}
  ].map(e=>({...e,planId:'engineer',type:'study',topic:'비교 주제',title:e.id,content:''}));
  events.push({id:'other-topic',planId:'engineer',type:'study',topic:'다른 주제',title:'다른 학습',date:TODAY,start:'00:00',end:'20:00',status:'done'});
  events.push({id:'other-plan',planId:'sqld',type:'study',topic:'비교 주제',title:'다른 계획',date:TODAY,start:'00:00',end:'20:00',status:'done'})`);
  await r.run("openPlanDetail('engineer')");
  creationClick(r,{planTopic:'비교 주제'});
  let html=r.node('#app').innerHTML;
  assert.match(html,/학습 주제 진행률 25퍼센트/);
  assert.match(html,/--expected-progress:50%/);
  assert.match(html,/예상보다 25%p 뒤처져 있어요/);
  creationClick(r,{topicStatus:'done'});
  assert.match(r.node('#app').innerHTML,/--expected-progress:50%/);
  assert.match(r.node('#app').innerHTML,/예상보다 25%p 뒤처져 있어요/);
  r.run("events.find(e=>e.id==='comparison-past').status='done';renderPlanDetail()");
  html=r.node('#app').innerHTML;
  assert.match(html,/학습 주제 진행률 50퍼센트/);
  assert.match(html,/계획대로 진행하고 있어요/);
  assert.doesNotMatch(html,/plan-progress-lag|plan-progress-lead/);
  r.run("Object.assign(events.find(e=>e.id==='comparison-future'),{status:'partial',progress:50});renderPlanDetail()");
  html=r.node('#app').innerHTML;
  assert.match(html,/학습 주제 진행률 75퍼센트/);
  assert.match(html,/예상보다 25%p 앞서 있어요/);
  assert.doesNotMatch(html,/plan-progress-lag|plan-progress-on-track/);
  r.run("events=events.filter(e=>e.topic!=='비교 주제');renderPlanDetail()");
  assert.match(r.node('#app').innerHTML,/--expected-progress:0%/);
  assert.doesNotMatch(r.node('#app').innerHTML,/NaN|Infinity/);
});

test('plan topic cards compare each topic independently with lag, lead and on-track messages',async()=>{
  const r=runtime();
  r.run(`events=['뒤처짐','계획대로','앞섬'].flatMap((topic,index)=>[
    {id:'card-past-'+index,date:TODAY,status:index===0?'unknown':'done',progress:0},
    {id:'card-future-'+index,date:'2026-11-26',status:index===2?'partial':'unknown',progress:index===2?50:0}
  ].map(e=>({...e,planId:'engineer',type:'study',topic,title:e.id,content:'',start:'09:00',end:'10:00'})));
  events.push({id:'other-plan',planId:'sqld',type:'study',topic:'뒤처짐',title:'다른 계획',date:TODAY,start:'00:00',end:'20:00',status:'done'})`);
  await r.run("openPlanDetail('engineer')");
  creationClick(r,{action:'topic-allocation-details'});
  const cards=r.node('#app').innerHTML.split('<div class="plan-topic-list">')[1].split('</section>');
  for(const [index,progress,message] of [[0,0,'예상보다 50%p 뒤처져 있어요.'],[1,50,'계획대로 진행하고 있어요.'],[2,75,'예상보다 25%p 앞서 있어요.']]){
    const card=cards[index];
    assert.match(card,/--expected-progress:50%/);
    assert.ok(card.includes(`value="${progress}" max="100"`));
    assert.ok(card.includes(message));
    assert.equal((card.match(/<progress /g)||[]).length,1);
  }
  creationClick(r,{action:'allocation-back'});creationClick(r,{topicStatus:'done'});creationClick(r,{action:'plan-section-back'});creationClick(r,{action:'topic-allocation-details'});
  assert.equal((r.node('#app').innerHTML.split('<div class="plan-topic-list">')[1].match(/--expected-progress:50%/g)||[]).length,3);
  creationClick(r,{planTopic:'앞섬'});
  assert.equal(r.run('selectedTopic'),'앞섬');
  assert.match(r.node('#app').innerHTML,/예상보다 25%p 앞서 있어요/);
});

test('compact sidebar plans show progress graphics, exam controls and detail links and refresh when reopened',async()=>{
  const r=runtime();
  r.run(`plans.push({id:'ahead',name:'앞선 계획',subtitle:'',active:true,examDate:'2026-12-03',color:'#559c6d'});
    events=['engineer','sqld','ahead'].flatMap((planId,index)=>[
      {id:planId+'-past',date:TODAY,status:index===0?'unknown':'done',progress:0},
      {id:planId+'-future',date:'2026-11-26',status:index===2?'partial':'unknown',progress:index===2?50:0}
    ].map(e=>({...e,planId,type:'study',topic:'공통 주제',title:e.id,content:'',start:'09:00',end:'10:00'})));
    plans.find(p=>p.id==='sqld').active=false;openPlansDrawer()`);
  const rows=r.node('#plans-list').innerHTML.split('</article>');
  for(const [index,progress,message] of [[0,0,'예상보다 50%p 뒤처져 있어요.'],[1,50,'계획대로 진행하고 있어요.'],[2,75,'예상보다 25%p 앞서 있어요.']]){
    const row=rows[index];
    assert.match(row,/sidebar-plan-progress/);
    assert.ok(row.includes(`sidebar-plan-progress ${['behind','on-track','ahead'][index]}`));
    assert.match(row,/--expected-progress:50%/);
    assert.ok(row.includes(`value="${progress}" max="100"`));
    assert.equal(row.includes(message),false);
    assert.doesNotMatch(row,/sidebar-progress-heading|plan-progress-lag|plan-progress-lead|plan-progress-on-track|plan-expected-progress|<strong>|학습 일정 \d+개/);
    assert.match(row,/data-edit-exam=/);
    assert.match(row,/data-open-plan=/);
    assert.match(row,/시험일 \d{4}\.\d{2}\.\d{2}/);
    assert.equal((row.match(/<progress /g)||[]).length,1);
  }
  assert.match(rows[1],/role="switch" aria-checked="false"/);
  await r.run('closePlansDrawer()');
  r.run("events.filter(e=>e.planId==='engineer').forEach(e=>e.status='done');openPlansDrawer()");
  assert.match(r.node('#plans-list').innerHTML.split('</article>')[0],/<progress value="100"/);
});

test('plan name editing validates input, supports cancellation and updates labels without changing study records',async()=>{
  const r=runtime();await r.run("openPlanDetail('engineer')");
  const before=r.run('JSON.stringify(events)'),name=r.run("plans.find(p=>p.id==='engineer').name");
  assert.match(r.node('#app').innerHTML,/data-action="rename-plan"/);
  assert.match(r.node('#app').innerHTML,/data-action="delete-plan"/);
  creationClick(r,{action:'rename-plan'});
  assert.equal(r.node('#rename-plan').open,true);
  assert.equal(r.node('#app').inert,true);
  assert.equal(r.node('#plan-name-input').value,name);
  r.node('#plan-name-input').value='취소할 이름';await r.node('#cancel-plan-name').handlers.click();
  assert.equal(r.run("plans.find(p=>p.id==='engineer').name"),name);
  creationClick(r,{action:'rename-plan'});
  r.node('#plan-name-input').value='   ';
  await r.node('#plan-name-form').handlers.submit({preventDefault(){}});
  assert.match(r.node('#plan-name-error').textContent,/1~40자/);
  assert.equal(r.node('#rename-plan').open,true);
  r.node('#plan-name-input').value='  새 계획 & <학습>  ';r.node('.detail-scroll').scrollTop=215;
  await r.node('#plan-name-form').handlers.submit({preventDefault(){}});
  assert.equal(r.run("plans.find(p=>p.id==='engineer').name"),'새 계획 & <학습>');
  assert.equal(r.node('#rename-plan').open,false);
  assert.equal(r.node('#app').inert,false);
  assert.match(r.node('#app').innerHTML,/새 계획 &amp; &lt;학습&gt;/);
  assert.match(r.node('#app').innerHTML,/class="plan-target-certificate">[\s\S]*data-certificate="engineer"/);
  assert.match(r.node('#app').innerHTML,/<span>정보처리기사 필기<\/span>/);
  assert.match(r.node('#plans-list').innerHTML,/새 계획 &amp; &lt;학습&gt;/);
  assert.equal(r.run("plans.find(p=>p.id==='engineer').certificateId"),'engineer');
  assert.match(r.node('#plans-list').innerHTML,/data-certificate="engineer" role="img" aria-label="목표 자격증: 정보처리기사 필기"/);
  assert.equal(r.node('.detail-scroll').scrollTop,215);
  assert.equal(r.run('JSON.stringify(events)'),before);
  r.run("openEvent('sample-19')");
  assert.match(r.node('#app').innerHTML,/class="plan-target-certificate">[\s\S]*data-certificate="engineer"/);
  assert.match(r.node('#app').innerHTML,/<span>정보처리기사 필기<\/span>/);
});

test('plan deletion cancels safely or removes only its linked study records and exam after confirmation',async()=>{
  const r=runtime();r.run("calendarView='week';renderCalendar()");r.node('.time-scroll').scrollTop=640;
  const before=r.run('JSON.stringify([plans,events])'),others=r.run("JSON.stringify(events.filter(e=>e.type!=='study'||e.planId!=='engineer'))");
  await r.run("openPlanDetail('engineer')");
  creationClick(r,{action:'delete-plan'});
  assert.equal(r.node('#delete-plan').open,true);
  assert.match(r.node('#delete-plan-message').textContent,/학습 기록.*학습 일정과 시험일/);
  await r.node('#cancel-delete-plan').handlers.click();
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  assert.equal(r.run('selectedPlan'),'engineer');
  creationClick(r,{action:'delete-plan'});
  await r.node('#confirm-delete-plan').handlers.click();
  assert.equal(r.run("plans.some(p=>p.id==='engineer')"),false);
  assert.equal(r.run("events.some(e=>e.type==='study'&&e.planId==='engineer')"),false);
  assert.equal(r.run('JSON.stringify(events)'),others);
  assert.equal(r.run("activeExamsOn('2026-11-30').some(p=>p.id==='engineer')"),false);
  assert.equal(r.run('selectedPlan'),null);
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.node('.time-scroll').scrollTop,640);
  assert.equal(r.node('#app').inert,false);
  assert.doesNotMatch(r.node('#plans-list').innerHTML,/data-open-plan="engineer"/);
});

test('plan deletion undo restores exact records, order, preferences, activation and exam without overwriting other edits',async()=>{
  for(const active of [false,true]){
    const r=runtime();
    r.run(`plans.find(p=>p.id==='engineer').active=${active};plans.find(p=>p.id==='engineer').name='이름 바꾼 계획';plans.find(p=>p.id==='engineer').preferences={snapshot:{weekly:['1:38']},weekOverrides:{extra:{'2026-11-22':['4:40']}}};events.find(e=>e.planId==='engineer').dismissedConflict='유지한 충돌';renderCalendar()`);
    const before=r.run('JSON.stringify([plans,events])');
    await r.run("openPlanDetail('engineer')");creationClick(r,{action:'toggle-plan-schedules'});r.node('.detail-scroll').scrollTop=460;r.node('.plan-schedule-list').scrollTop=73;
    creationClick(r,{action:'delete-plan'});await r.node('#confirm-delete-plan').handlers.click();
    assert.equal(r.node('#plan-delete-undo').classList.contains('visible'),true);
    assert.equal(r.node('#plan-delete-undo').inert,false);
    r.node('.time-scroll').scrollTop=718;
    await r.node('#undo-plan-delete').handlers.click();
    assert.equal(r.run('JSON.stringify([plans,events])'),before);
    assert.equal(r.run('selectedPlan'),'engineer');
    assert.equal(r.run('selectedEvent'),null);
    assert.equal(r.node('.detail-scroll').scrollTop,460);
    assert.equal(r.node('.plan-schedule-list').scrollTop,73);
    assert.equal(r.run('planSchedulesExpanded'),true);
    assert.match(r.node('#app').innerHTML,/학습 계획 상세/);
    assert.equal(r.node('#plan-delete-undo').classList.contains('visible'),false);
    assert.equal(r.node('#plan-delete-undo').inert,true);
    assert.equal(r.run("activeExamsOn('2026-11-30').some(p=>p.id==='engineer')"),active);
    await r.node('#undo-plan-delete').handlers.click();
    assert.equal(r.run('JSON.stringify([plans,events])'),before);
    creationClick(r,{action:'plan-back'});
    assert.equal(r.run('calendarView'),'month');
    assert.equal(r.run('selectedDate'),'2026-11-25');
    await r.run("openPlanDetail('engineer')");r.node('.detail-scroll').scrollTop=345;
    creationClick(r,{action:'delete-plan'});await r.node('#confirm-delete-plan').handlers.click();
    const otherId=r.run("events.find(e=>e.planId==='sqld'&&e.type==='study').id");
    r.run(`openEvent('${otherId}');setStatus('done')`);r.node('.detail-scroll').scrollTop=218;
    await r.node('#undo-plan-delete').handlers.click();
    assert.equal(r.run('selectedEvent'),null);
    assert.equal(r.run('selectedPlan'),'engineer');
    assert.equal(r.run(`events.find(e=>e.id==='${otherId}').status`),'done');
    assert.equal(r.node('.detail-scroll').scrollTop,345);
    assert.match(r.node('#app').innerHTML,/학습 계획 상세/);
    assert.equal(r.run("plans.find(p=>p.id==='engineer').name"),'이름 바꾼 계획');
  }
});

test('plan deletion undo expires and resetting the prototype discards the pending undo',async()=>{
  for(const reset of [false,true]){
    const r=runtime();
    r.run("let undoExpiry=null;const savedSetTimeout=setTimeout;setTimeout=(fn,delay)=>{if(delay===8000)undoExpiry=fn;return savedSetTimeout(fn,delay)}");
    await r.run("openPlanDetail('engineer')");creationClick(r,{action:'delete-plan'});await r.node('#confirm-delete-plan').handlers.click();
    assert.equal(r.run('typeof undoExpiry'),'function');
    if(reset){r.run('globalThis.confirm=()=>true');r.node('#reset').handlers.click();}
    else r.run('undoExpiry()');
    assert.equal(r.run('deletedPlanUndo'),null);
    assert.equal(r.node('#plan-delete-undo').classList.contains('visible'),false);
    const before=r.run('JSON.stringify([plans,events])');r.node('#undo-plan-delete').handlers.click();
    assert.equal(r.run('JSON.stringify([plans,events])'),before);
    assert.equal(r.run("plans.some(p=>p.id==='engineer')"),reset);
  }
});

test('sidebar plan opens its detail and returning from study details refreshes topic statistics', async () => {
  const r=runtime(),originalDate=r.run('selectedDate');
  await r.node('#plans-list').handlers.click({target:{closest:selector=>selector==='[data-open-plan]'?{dataset:{openPlan:'engineer'}}:null}});
  assert.equal(r.run('selectedPlan'),'engineer');
  assert.match(r.node('#app').innerHTML,/학습 주제별 시간 배정/);
  const progress=r.run("planStatistics('engineer').progress");
  r.run("openEvent('sample-19');setStatus('done')");
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{action:'back'}})}});
  assert.equal(r.run('selectedEvent'),null);
  assert.equal(r.run('selectedPlan'),'engineer');
  assert.ok(r.run("planStatistics('engineer').progress")>progress);
  assert.match(r.node('#app').innerHTML,/학습 주제별 시간 배정/);
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{action:'plan-back'}})}});
  assert.equal(r.run('selectedPlan'),null);
  assert.equal(r.run('selectedDate'),originalDate);
  assert.match(r.node('#app').innerHTML,/월간 달력/);
});

test('topic detail counts all four states by schedule count and lists only its topic and plan in date order', async () => {
  const r=runtime();
  r.run(`events.push(...['done','partial','missed','unknown'].map((status,index)=>({id:'topic-'+index,planId:'engineer',topic:'통합 주제',title:'학습 '+index,type:'study',date:'2026-11-'+(29-index),start:'09:00',end:'10:00',status,progress:status==='partial'?25:0,content:'예시'})),{id:'other-topic',planId:'sqld',topic:'통합 주제',title:'다른 계획',type:'study',date:TODAY,start:'09:00',end:'10:00',status:'done'})`);
  await r.run("openPlanDetail('engineer')");
  creationClick(r,{action:'topic-allocation-details'});
  creationClick(r,{planTopic:'통합 주제'});
  assert.equal(r.run('selectedTopic'),'통합 주제');
  const html=r.node('#app').innerHTML;
  assert.match(html,/class="plan-target-certificate">[\s\S]*data-certificate="engineer"/);
  assert.match(html,/<span>정보처리기사 필기<\/span>/);
  assert.match(html,/<svg viewBox="0 0 142 142"/);
  assert.match(html,/다 했어요 1개, 일부 했어요 1개, 못 했어요 1개, 미확인 1개/);
  assert.equal((html.match(/data-locate-event=/g)||[]).length,4);
  assert.ok(html.indexOf('data-locate-event="topic-3"')<html.indexOf('data-locate-event="topic-0"'));
  assert.doesNotMatch(html,/다른 계획|data-locate-event="other-topic"/);
  creationClick(r,{action:'topic-back'});
  assert.equal(r.run('selectedTopic'),null);
  assert.match(r.node('#app').innerHTML,/학습 주제별 현황/);
});

test('locating a topic schedule opens its week, scrolls to its time and removes temporary highlighting without changing records', async () => {
  const r=runtime(),before=r.run('JSON.stringify([plans,events])');await r.run("openPlanDetail('engineer')");
  creationClick(r,{planTopic:'모의 시험'});
  creationClick(r,{locateEvent:'sample-19'});
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.run('selectedDate'),'2026-11-26');
  assert.equal(r.run('selectedPlan'),null);
  assert.equal(r.run('selectedTopic'),null);
  assert.equal(r.run('selectedEvent'),null);
  assert.equal(r.run('calendarSpotlight'),'sample-19');
  assert.match(r.node('#app').innerHTML,/calendar-toolbar view-week/);
  assert.match(r.node('#app').innerHTML,/calendar-spotlight/);
  assert.equal(r.node('.time-scroll').scrollTop,750);
  r.run("finishCalendarSpotlight('sample-19');renderCalendar()");
  assert.equal(r.run('calendarSpotlight'),null);
  assert.doesNotMatch(r.node('#app').innerHTML,/calendar-spotlight/);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

test('locating an inactive plan schedule temporarily reveals only that schedule and preserves the plan switch', async () => {
  const r=runtime();r.run("setPlanActive('engineer',false)");await r.run("openPlanDetail('engineer')");
  creationClick(r,{planTopic:'모의 시험'});creationClick(r,{locateEvent:'sample-19'});
  assert.equal(r.run("plans.find(p=>p.id==='engineer').active"),false);
  assert.equal(r.run("isEventVisible(events.find(e=>e.id==='sample-19'))"),true);
  assert.equal(r.run("isEventVisible(events.find(e=>e.id==='sample-20'))"),false);
  r.run("finishCalendarSpotlight('sample-19')");
  assert.equal(r.run("isEventVisible(events.find(e=>e.id==='sample-19'))"),false);
  assert.doesNotMatch(r.node('#app').innerHTML,/data-event="sample-19"/);
});

test('back from a located week restores the originating topic, list position and original plan return context', async () => {
  const r=runtime(),originalDate=r.run('selectedDate');
  await r.run("openPlanDetail('engineer')");creationClick(r,{planTopic:'모의 시험'});
  r.node('.detail-scroll').scrollTop=320;
  creationClick(r,{locateEvent:'sample-19'});
  assert.match(r.node('#app').innerHTML,/data-action="return-topic"/);
  r.run("finishCalendarSpotlight('sample-19')");
  creationClick(r,{action:'return-topic'});
  assert.equal(r.run('selectedPlan'),'engineer');
  assert.equal(r.run('selectedTopic'),'모의 시험');
  assert.equal(r.run('topicCalendarReturn'),null);
  assert.equal(r.node('.detail-scroll').scrollTop,320);
  assert.match(r.node('#app').innerHTML,/학습 주제 상세/);
  assert.match(r.node('#app').innerHTML,/data-locate-event="sample-19"/);
  creationClick(r,{action:'topic-back'});creationClick(r,{action:'plan-back'});
  assert.equal(r.run('selectedDate'),originalDate);
  assert.equal(r.run('calendarView'),'month');
});

test('Escape from a located week returns to an inactive plan topic without changing activation', async () => {
  const r=runtime();r.run("setPlanActive('engineer',false)");
  await r.run("openPlanDetail('engineer')");creationClick(r,{planTopic:'모의 시험'});
  creationClick(r,{locateEvent:'sample-19'});
  r.node('#app').handlers.keydown({key:'Escape',target:{closest:()=>null},preventDefault(){}});
  assert.equal(r.run('selectedTopic'),'모의 시험');
  assert.equal(r.run('selectedPlan'),'engineer');
  assert.equal(r.run('calendarSpotlight'),null);
  assert.equal(r.run("plans.find(p=>p.id==='engineer').active"),false);
});

async function filterTopicRuntime(){
  const r=runtime();
  r.run(`events.push(...['done','partial','missed','unknown'].map((status,index)=>({id:'filter-'+status,planId:'engineer',topic:'필터 주제',title:status,type:'study',date:'2026-11-'+(26+index),start:'09:00',end:'10:00',status,progress:50,content:'예시'})))`);
  await r.run("openPlanDetail('engineer')");creationClick(r,{planTopic:'필터 주제'});return r;
}

test('topic legend and chart segments toggle the same status filter while chart totals stay unchanged', async () => {
  const r=await filterTopicRuntime();
  for(const status of ['done','partial','missed','unknown']){
    creationClick(r,{topicStatus:status});
    assert.equal(r.run('topicStatusFilter'),status);
    const html=r.node('#app').innerHTML;
    assert.equal((html.match(/data-locate-event=/g)||[]).length,1);
    assert.match(html,new RegExp(`data-locate-event="filter-${status}"`));
    assert.match(html,/다 했어요 1개, 일부 했어요 1개, 못 했어요 1개, 미확인 1개/);
    assert.match(html,/1개 \/ 전체 4개/);
    const slice={dataset:{topicStatus:status},tagName:'path'};
    r.node('#app').handlers.click({target:{closest:selector=>selector==='[data-topic-status]'?slice:null}});
    assert.equal(r.run('topicStatusFilter'),null);
    assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,4);
  }
});

test('topic filter supports empty states, keyboard graph selection, total reset and restoration after calendar navigation', async () => {
  const r=await filterTopicRuntime();
  r.run("events=events.filter(e=>e.id!=='filter-missed');renderPlanDetail()");
  creationClick(r,{topicStatus:'missed'});
  assert.match(r.node('#app').innerHTML,/해당 상태의 일정이 없어요/);
  assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,0);
  creationClick(r,{topicStatus:'all'});
  assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,3);
  r.node('#app').handlers.keydown({key:'Enter',preventDefault(){},target:{closest:()=>({dataset:{topicStatus:'partial'},tagName:'path'})}});
  assert.equal(r.run('topicStatusFilter'),'partial');
  creationClick(r,{locateEvent:'filter-partial'});creationClick(r,{action:'return-topic'});
  assert.equal(r.run('topicStatusFilter'),'partial');
  assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,1);
  creationClick(r,{topicStatus:'partial'});
  assert.equal(r.run('topicStatusFilter'),null);
  assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,3);
});

test('sidebar conflict button and menu dot track conflicts and open the conflict list after closing the drawer', async () => {
  const r=runtime();
  r.run("events=[{id:'study',planId:'engineer',type:'study',date:TODAY,start:'09:00',end:'10:00',title:'학습',status:'unknown',content:''},{id:'meeting',type:'calendar',date:TODAY,start:'09:30',end:'10:30',title:'회의',status:'unknown',content:''}];renderCalendar()");
  assert.match(r.run('conflictIndicator()'),/conflict-indicator active/);
  assert.match(r.run('conflictIndicator()'),/충돌 일정 1개 확인/);
  assert.match(r.node('#app').innerHTML,/plans-conflict-dot/);
  assert.doesNotMatch(r.node('#app').innerHTML,/data-action="calendar-conflicts"/);
  r.run('openPlansDrawer()');
  assert.match(r.node('#drawer-conflicts').innerHTML,/conflict-indicator active/);
  await r.node('#drawer-conflicts').handlers.click({target:{closest:()=>({dataset:{action:'calendar-conflicts'}})}});
  assert.equal(r.run('plansDrawerOpen'),false);
  assert.equal(r.node('#calendar-conflicts').open,true);
  assert.match(r.node('#calendar-conflict-list').innerHTML,/학습/);
  assert.match(r.node('#calendar-conflict-list').innerHTML,/회의/);
  r.run("events[0].dismissedConflict=conflictSignature(events[0]);renderCalendar()");
  assert.match(r.run('conflictIndicator()'),/disabled aria-disabled="true"/);
  r.run("events[0].dismissedConflict='';setPlanActive('engineer',false)");
  assert.match(r.run('conflictIndicator()'),/충돌 일정 없음/);
  r.run("setPlanActive('engineer',true)");
  assert.match(r.run('conflictIndicator()'),/conflict-indicator active/);
  r.run("events[0].start='11:00';events[0].end='12:00';renderCalendar()");
  assert.match(r.run('conflictIndicator()'),/disabled aria-disabled="true"/);
});

test('conflict button is only in the sidebar and the menu dot is absent when there are no conflicts', () => {
  const r=runtime();r.run("events=[]");
  for(const view of ['year','month','week','day']){
    r.run(`calendarView='${view}';renderCalendar()`);
    assert.doesNotMatch(r.node('#app').innerHTML,/data-action="calendar-conflicts"|plans-conflict-dot/);
    r.run('renderPlanList()');
    assert.match(r.node('#drawer-conflicts').innerHTML,/disabled aria-disabled="true"/);
  }
});

test('clicking a sidebar conflict-list schedule closes the popup and locates its week with temporary emphasis', async () => {
  const r=runtime(),before=r.run('JSON.stringify([plans,events])');
  r.run('openPlansDrawer()');
  await r.node('#drawer-conflicts').handlers.click({target:{closest:()=>({dataset:{action:'calendar-conflicts'}})}});
  assert.equal(r.node('#calendar-conflicts').open,true);
  assert.match(r.node('#calendar-conflict-list').innerHTML,/data-conflict-event="sample-19"/);
  r.node('#calendar-conflicts').scrollTop=120;
  r.node('#calendar-conflict-list').scrollTop=40;
  await r.node('#calendar-conflict-list').handlers.click({target:{closest:()=>({dataset:{conflictEvent:'sample-19'}})}});
  assert.equal(r.node('#calendar-conflicts').open,false);
  assert.equal(r.run('plansDrawerOpen'),false);
  assert.equal(r.node('#app').inert,false);
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.run('selectedDate'),'2026-11-26');
  assert.equal(r.run('calendarSpotlight'),'sample-19');
  assert.equal(r.run('selectedEvent'),null);
  assert.equal(r.run('topicCalendarReturn'),null);
  assert.match(r.node('#app').innerHTML,/id="calendar-return-notice"/);
  assert.match(r.node('#app').innerHTML,/data-action="return-conflicts"/);
  assert.match(r.node('#app').innerHTML,/calendar-spotlight/);
  assert.equal(r.node('.time-scroll').scrollTop,750);
  r.run("finishCalendarSpotlight('sample-19');renderCalendar()");
  assert.doesNotMatch(r.node('#app').innerHTML,/calendar-spotlight/);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);

});

test('bottom conflict return restores the original conflict list and its scroll position', async () => {
  const r=runtime();r.run('showCalendarConflicts()');
  r.node('#calendar-conflicts').scrollTop=120;
  await r.node('#calendar-conflict-list').handlers.click({target:{closest:()=>({dataset:{conflictEvent:'sample-19'}})}});
  creationClick(r,{action:'return-conflicts'});
  assert.equal(r.node('#calendar-conflicts').open,true);
  assert.equal(r.node('#calendar-conflicts').scrollTop,120);
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('conflictCalendarReturn'),null);
});

for(const source of ['topic','conflict','plan'])test(`${source} calendar return appears only at the bottom and dismisses when another area is clicked`, async () => {
  const r=runtime();
  if(source==='topic' || source==='plan'){
    await r.run("openPlanDetail('engineer')");if(source==='topic')creationClick(r,{planTopic:'모의 시험'});else creationClick(r,{action:'toggle-plan-schedules'});creationClick(r,{locateEvent:'sample-19'});
  }else{
    r.run('showCalendarConflicts()');
    await r.node('#calendar-conflict-list').handlers.click({target:{closest:()=>({dataset:{conflictEvent:'sample-19'}})}});
  }
  const html=r.node('#app').innerHTML,header=html.slice(0,html.indexOf('</header>'));
  assert.doesNotMatch(header,/return-topic|return-conflicts/);
  assert.match(html,/id="calendar-return-notice"/);
  assert.ok(html.indexOf('id="calendar-return-notice"')>html.indexOf('class="time-view'));
  r.run("document.handlers.pointerdown({target:{closest:()=>document.querySelector('#calendar-return-notice')}})");
  assert.notEqual(r.run('calendarReturnNotice()'),'');
  r.run('document.handlers.pointerdown({target:{closest:()=>null}})');
  assert.equal(r.run('calendarReturnNotice()'),'');
  assert.equal(r.node('#calendar-return-notice').removed,true);
  assert.equal(r.node('.phone').classList.contains('has-calendar-return'),false);
  assert.equal(r.run('calendarView'),'week');
  assert.equal(r.run('selectedPlan'),null);
});

test('calendar return notice animates out even when returning immediately replaces the calendar screen', async () => {
  const r=runtime();await r.run("openPlanDetail('engineer')");creationClick(r,{planTopic:'모의 시험'});creationClick(r,{locateEvent:'sample-19'});
  r.run("window.matchMedia=()=>({matches:false})");
  const notice=r.node('#calendar-return-notice'),animations=[];let finish;
  notice.animate=(frames,options)=>{animations.push({frames,options});return {finished:new Promise(resolve=>{finish=resolve;}),cancel(){}};};
  creationClick(r,{action:'return-topic'});
  assert.equal(r.run('selectedTopic'),'모의 시험');
  assert.match(r.node('#app').innerHTML,/학습 주제 상세/);
  assert.equal(r.run('topicCalendarReturn'),null);
  assert.equal(notice.inert,true);
  assert.equal(notice.classList.contains('calendar-return-closing'),true);
  assert.ok(r.node('.phone').children.includes(notice));
  assert.equal(animations[0].options.duration,240);
  assert.equal(animations[0].frames.at(-1).opacity,0);
  assert.match(animations[0].frames.at(-1).transform,/translateY\(20px\)/);
  assert.notEqual(notice.removed,true);
  finish();await Promise.resolve();await Promise.resolve();
  assert.equal(notice.removed,true);
});

test('plan chart and legend filter all plan schedules and toggle back to the complete list', async () => {
  const r=await filterTopicRuntime();creationClick(r,{action:'topic-back'});
  const total=r.run("planStatistics('engineer').studies.length");
  for(const status of ['done','partial','missed','unknown']){
    const expected=r.run(`planStatistics('engineer').studies.filter(e=>e.status==='${status}').length`);
    creationClick(r,{topicStatus:status});
    assert.equal(r.run('planStatusFilter'),status);
    assert.equal(r.run('topicStatusFilter'),null);
    assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,expected);
    assert.match(r.node('#app').innerHTML,new RegExp(`${expected}개 / 전체 ${total}개`));
    assert.doesNotMatch(r.node('#app').innerHTML,/data-locate-event="sample-26"/);
    const slice={dataset:{topicStatus:status},tagName:'path'};
    r.node('#app').handlers.click({target:{closest:selector=>selector==='[data-topic-status]'?slice:null}});
    assert.equal(r.run('planStatusFilter'),null);
    assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,total);
  }
});

test('plan status filters handle zero results, remain independent of topic filters and refresh after learning result edits', async () => {
  const r=await filterTopicRuntime();creationClick(r,{action:'topic-back'});
  creationClick(r,{topicStatus:'partial'});
  creationClick(r,{planTopic:'필터 주제'});creationClick(r,{topicStatus:'done'});
  assert.equal(r.run('topicStatusFilter'),'done');
  assert.equal(r.run('planStatusFilter'),'partial');
  creationClick(r,{action:'topic-back'});
  assert.equal(r.run('planStatusFilter'),'partial');
  r.run("events=events.filter(e=>e.planId!=='engineer'||e.id==='filter-partial');renderPlanDetail()");
  creationClick(r,{event:'filter-partial'});r.run("setStatus('done')");creationClick(r,{action:'back'});
  assert.equal(r.run('planStatusFilter'),'partial');
  assert.match(r.node('#app').innerHTML,/해당 상태의 일정이 없어요/);
  assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,0);
  creationClick(r,{topicStatus:'all'});
  assert.equal(r.run('planStatusFilter'),null);
  assert.match(r.node('#app').innerHTML,/data-locate-event="filter-partial"/);
});

test('plan schedule list locates the schedule week and bottom return preserves every status filter and both scroll positions', async () => {
  for(const status of [null,'done','partial','missed','unknown']){
    const r=await filterTopicRuntime();creationClick(r,{action:'topic-back'});
    if(status)creationClick(r,{topicStatus:status});else creationClick(r,{action:'toggle-plan-schedules'});
    r.node('.detail-scroll').scrollTop=240;r.node('.plan-schedule-list').scrollTop=120;
    const id=`filter-${status || 'unknown'}`,before=r.run('JSON.stringify([plans,events])');
    creationClick(r,{locateEvent:id});
    assert.equal(r.run('calendarView'),'week');
    assert.equal(r.run('selectedPlan'),null);
    assert.equal(r.run('calendarSpotlight'),id);
    assert.match(r.node('#app').innerHTML,/calendar-spotlight/);
    assert.match(r.node('#app').innerHTML,/학습 계획 상세로 돌아가기/);
    assert.doesNotMatch(r.node('#app').innerHTML,/학습 주제 상세로 돌아가기/);
    creationClick(r,{action:'return-topic'});
    assert.equal(r.run('selectedPlan'),'engineer');
    assert.equal(r.run('selectedTopic'),null);
    assert.equal(r.run('planStatusFilter'),status);
    assert.equal(r.node('.detail-scroll').scrollTop,240);
    assert.equal(r.node('.plan-schedule-list').scrollTop,120);
    assert.match(r.node('#app').innerHTML,status?/plan-status-detail/:/학습 계획 상세/);
    assert.match(r.node('#app').innerHTML,new RegExp(`data-locate-event="${id}"`));
    assert.equal(r.run('JSON.stringify([plans,events])'),before);
  }
});

test('plan schedules start collapsed, manually toggle and automatically expand when a status filter is applied', async () => {
  const r=runtime(),before=r.run('JSON.stringify([plans,events])');await r.run("openPlanDetail('engineer')");
  assert.equal(r.run('planSchedulesExpanded'),false);
  assert.match(r.node('#app').innerHTML,/aria-expanded="false" aria-controls="plan-schedules-panel"/);
  assert.match(r.node('#app').innerHTML,/class="plan-schedules-panel" inert aria-hidden="true"/);
  creationClick(r,{action:'toggle-plan-schedules'});
  assert.equal(r.run('planSchedulesExpanded'),true);
  creationClick(r,{action:'toggle-plan-schedules'});
  creationClick(r,{topicStatus:'partial'});
  assert.equal(r.run('planSchedulesExpanded'),true);
  assert.equal(r.run('planStatusFilter'),'partial');
  creationClick(r,{action:'toggle-plan-schedules'});
  assert.equal(r.run('planSchedulesExpanded'),false);
  creationClick(r,{topicStatus:'done'});
  assert.equal(r.run('planSchedulesExpanded'),true);
  creationClick(r,{topicStatus:'done'});
  assert.equal(r.run('planSchedulesExpanded'),true);
  assert.equal(r.run('planStatusFilter'),null);
  creationClick(r,{action:'plan-back'});await r.run("openPlanDetail('engineer')");
  assert.equal(r.run('planSchedulesExpanded'),false);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

for(const screen of ['plan','topic'])test(`${screen} status filtering crossfades schedule content and animates height, including rapid changes`, async () => {
  const r=await filterTopicRuntime();if(screen==='plan'){creationClick(r,{action:'topic-back'});creationClick(r,{action:'plan-status-details'});}
  r.run('window.matchMedia=()=>({matches:false})');
  const target=r.node(screen==='plan'?'.plan-schedules-panel':'.topic-schedule-list'),content=r.node('.detail-schedule-content'),ghost=r.node('floating-ghost');
  target.getBoundingClientRect=()=>({height:r.run(screen==='plan'?'planStatusFilter':'topicStatusFilter')?120:300});
  const animations=[];
  const animate=(frames,options)=>{let resolve;const animation={finished:new Promise(done=>{resolve=done;}),cancel(){this.cancelled=true;resolve();},finish(){resolve();}};animations.push({frames,options,animation});return animation;};
  target.animate=animate;content.animate=animate;ghost.animate=animate;
  creationClick(r,{topicStatus:'done'});
  assert.equal(animations.length,3);
  assert.equal(animations[0].frames[0].height,'300px');
  assert.equal(animations[0].frames[1].height,'120px');
  assert.equal(animations[0].options.duration,280);
  assert.equal(animations[1].frames[0].opacity,0);
  assert.equal(animations[2].frames[1].opacity,0);
  creationClick(r,{topicStatus:'partial'});
  assert.equal(animations.slice(0,3).every(item=>item.animation.cancelled),true);
  animations.forEach(item=>item.animation.finish());
  await Promise.resolve();await Promise.resolve();await Promise.resolve();await Promise.resolve();
  assert.equal(r.run('detailListMotions.size'),0);
});

test('plan detail exam edit uses the existing editor and adjustment preview, returning to the plan on cancellation', async () => {
  const r=runtime();await r.run("openPlanDetail('engineer')");
  assert.match(r.node('#app').innerHTML,/class="plan-exam-change" data-edit-exam="engineer"/);
  const before=r.run('JSON.stringify(events)');
  creationClick(r,{editExam:'engineer'});
  assert.equal(r.node('#edit-exam').open,true);
  assert.equal(r.node('#exam-date-input').value,'2026-11-30');
  r.node('#exam-date-input').value='2026-12-04';
  await r.run('saveExamDate()');
  assert.equal(r.run("plans.find(p=>p.id==='engineer').examDate"),'2026-12-04');
  assert.equal(r.run('selectedPlan'),'engineer');
  assert.match(r.node('#app').innerHTML,/2026년 12월 4일/);
  assert.equal(r.node('#adjust-plan').open,true);
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.run('previewPlanAdjustment()');
  assert.ok(r.run('planCalendarPreview'));
  assert.match(r.node('#app').innerHTML,/월간 달력/);
  assert.doesNotMatch(r.node('#app').innerHTML,/class="detail plan-detail"/);
  await r.run('cancelPlanAdjustment()');
  assert.equal(r.run('planCalendarPreview'),null);
  assert.match(r.node('#app').innerHTML,/학습 계획 상세/);
  assert.match(r.node('#app').innerHTML,/2026년 12월 4일/);
  assert.equal(r.run('JSON.stringify(events)'),before);
});

async function remainingReplanRuntime(){
  const r=runtime();
  r.run(`plans.push({id:'replan',certificateId:'engineer',name:'재배정 계획',subtitle:'남은 학습',active:true,examDate:'2026-12-02',preferences:{windows:[{day:4,start:'19:00',end:'21:00'},{day:5,start:'19:00',end:'21:00'}]}});
    events.push(...[
      {id:'rp-past-missed',date:'2026-11-24',start:'09:00',end:'10:00',status:'missed',progress:0},
      {id:'rp-past-partial',date:'2026-11-24',start:'10:00',end:'11:00',status:'partial',progress:50},
      {id:'rp-done',date:'2026-11-26',start:'09:00',end:'10:00',status:'done',progress:100},
      {id:'rp-unknown',date:'2026-11-26',start:'11:00',end:'12:00',status:'unknown',progress:0},
      {id:'rp-missed',date:'2026-11-27',start:'14:00',end:'15:00',status:'missed',progress:0},
      {id:'rp-partial',date:'2026-11-28',start:'10:00',end:'12:00',status:'partial',progress:25}
    ].map(e=>({...e,planId:'replan',type:'study',topic:'핵심 개념',title:e.id,content:'예시 학습'})));
    events.push({id:'rp-busy',type:'calendar',date:'2026-11-26',start:'19:30',end:'20:00',title:'기존 약속',content:'',status:'unknown'})`);
  await r.run("openPlanDetail('replan')");r.run("planStatusFilter='partial';planSchedulesExpanded=true;renderPlanDetail()");return r;
}
function editRemainingPreferences(r){
  creationClick(r,{creationMode:'excluded'});creationStroke(r,4,41,4,41);
  creationClick(r,{creationMode:'extra'});creationStroke(r,6,22,6,27);
}

test('remaining replan compares per-topic allocated hours before opening the calendar preview',async()=>{
  const r=await remainingReplanRuntime(),before=r.run('JSON.stringify([plans,events])');
  r.run("events.find(e=>e.id==='rp-unknown').topic='SQL 활용'");
  const edited=r.run('JSON.stringify([plans,events])');
  r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);
  creationClick(r,{planCreateAction:'next'});
  const html=r.node('#app').innerHTML;
  assert.equal(r.run('planCreation.replanComparison'),true);
  assert.match(html,/주제별 배정 시간 비교/);
  assert.match(html,/핵심 개념/);
  assert.match(html,/SQL 활용/);
  assert.match(html,/변경 전 1시간, 변경 후 1시간/);
  assert.match(html,/변경 전 6시간, 변경 후 7시간 30분/);
  assert.match(html,/전체 배정 시간 <strong>7시간 → 8시간 30분/);
  assert.equal(r.run('JSON.stringify([plans,events])'),edited);
  creationClick(r,{planCreateAction:'back'});
  assert.equal(r.run('planCreation.preview'),null);
  assert.match(r.node('#app').innerHTML,/새 공부 선호 시간/);
  creationClick(r,{planCreateAction:'next'});
  creationClick(r,{planCreateAction:'show-replan-preview'});
  assert.equal(r.run('calendarView'),'week');
  assert.match(r.node('#app').innerHTML,/새 배정/);
  assert.equal(r.run('JSON.stringify([plans,events])'),edited);
});

test('example plans without saved preferences can extend default study hours to include historical remaining work', async () => {
  const r=runtime();await r.run("openPlanDetail('engineer')");
  const before=r.run('JSON.stringify([plans,events])');r.run('openRemainingReplan(selectedPlan)');creationStroke(r,6,24,6,27);creationStroke(r,3,42,3,45);creationStroke(r,4,42,4,45);creationClick(r,{planCreateAction:'next'});
  assert.ok(r.run('planCreation.preview'),r.node('#plan-create-error').textContent);
  assert.equal(r.run('planCreation.preview.events.every(e=>e.date>=TODAY && e.date<planCreation.examDate && !hasConflict(e))'),true);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  creationClick(r,{planCreateAction:'cancel-preview'});creationClick(r,{planCreateAction:'cancel'});
  assert.equal(r.run('selectedPlan'),'engineer');
});

test('replanning displays replacement originals separately and highlights either group without changing records or conflicts',async()=>{
  const r=await remainingReplanRuntime(),before=r.run('JSON.stringify([plans,events])');
  r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  assert.ok(r.run('planCreation.preview'));
  assert.match(r.node('#app').innerHTML,/대체 전/);
  assert.doesNotMatch(r.node('#app').innerHTML,/class="replan-exam-change"/);
  assert.match(r.node('#app').innerHTML,/새 배정/);
  assert.match(r.node('#app').innerHTML,/data-event="replan-original-rp-unknown" disabled/);
  assert.equal(r.run('replanOriginalEvents().length'),r.run('planCreation.preview.replaceIds.size'));
  assert.equal(r.run('calendarEvents().some(e=>e.isReplacementOriginal)'),false);
  r.run("Object.assign(planCreation.preview.events[0],{date:'2026-11-26',start:'11:00',end:'12:00',dismissedConflict:''});renderCalendar()");
  assert.equal(r.run('hasConflict(planCreation.preview.events[0])'),false);
  assert.equal(r.run('hasConflict(replanOriginalEvents()[0])'),false);
  const original=r.node('test-original-group'),newEvent=r.node('test-new-group');
  original.dataset.replanEventGroup='original';newEvent.dataset.replanEventGroup='new';
  const query=r.node('#app').querySelectorAll,html=r.node('#app').innerHTML,scrolls=[];
  r.node('#app').querySelectorAll=selector=>selector==='[data-replan-event-group]'?[original,newEvent]:query(selector);
  r.node('.time-scroll').scrollTo=options=>scrolls.push(options);
  r.run('window.matchMedia=()=>({matches:false})');
  creationClick(r,{replanGroup:'original'});
  assert.equal(r.run('planCreation.preview.highlightGroup'),'original');
  assert.equal(original.classList.contains('replan-group-highlight'),true);
  assert.equal(newEvent.classList.contains('replan-group-muted'),true);
  creationClick(r,{replanGroup:'new'});
  assert.equal(r.run('planCreation.preview.highlightGroup'),'new');
  assert.equal(newEvent.classList.contains('replan-group-highlight'),true);
  assert.equal(original.classList.contains('replan-group-muted'),true);
  assert.equal(r.node('#app').innerHTML,html);
  assert.equal(scrolls.every(options=>options.behavior==='smooth'),true);
  r.run('document.handlers.pointerdown({target:{closest:()=>null}})');
  assert.equal(r.run('planCreation.preview.highlightGroup'),null);
  assert.equal(original.classList.contains('replan-group-muted'),false);
  assert.equal(newEvent.classList.contains('replan-group-highlight'),false);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  creationClick(r,{planCreateAction:'cancel-preview'});
  assert.equal(r.run('replanOriginalEvents().length'),0);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

test('remaining replan exam date stays in the draft and controls allocation until final confirmation',async()=>{
  for(const apply of [false,true]){
    const r=await remainingReplanRuntime(),before=r.run('JSON.stringify([plans,events])');
    const history=r.run("JSON.stringify(events.filter(e=>e.planId==='replan'&&(e.date<TODAY||e.status==='done')))");
    r.run('openRemainingReplan(selectedPlan)');
    assert.match(r.node('#app').innerHTML,/data-plan-field="examDate"/);
    creationInput(r,'startDate','2026-12-03');creationInput(r,'examDate','2026-12-12');
    creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
    assert.ok(r.run('planCreation.preview'));
    assert.equal(r.run('planCreation.preview.plan.examDate'),'2026-12-12');
    assert.match(r.node('#app').innerHTML,/변경 전 시험일<\/small><time datetime="2026-12-02">2026\.12\.02/);
    assert.match(r.node('#app').innerHTML,/변경 후 시험일<\/small><time datetime="2026-12-12">2026\.12\.12/);
    assert.equal(r.run("plans.find(p=>p.id==='replan').examDate"),'2026-12-02');
    assert.equal(r.run("planCreation.preview.events.every(e=>e.date>='2026-12-03' && e.date<'2026-12-12')"),true);
    assert.equal(r.run("activeExamsOn('2026-12-02').some(p=>p.id==='replan')"),false);
    assert.equal(r.run("activeExamsOn('2026-12-12').some(p=>p.id==='replan')"),true);
    assert.equal(r.run('JSON.stringify([plans,events])'),before);
    r.run("let examExpiry=null;const examSetTimeout=setTimeout;setTimeout=(fn,delay)=>{if(delay===3000)examExpiry=fn;return examSetTimeout(fn,delay)}");
    r.node('.time-scroll').scrollTop=690;
    creationClick(r,{replanExam:'before'});
    assert.equal(r.run('selectedDate'),'2026-12-02');
    assert.equal(r.run('creationWeekKey(dateKey(viewDate))'),'2026-11-29');
    assert.equal(r.run('planCreation.preview.examSpotlight.kind'),'before');
    assert.equal(r.node('[data-replan-exam-marker="before"]').classList.contains('replan-exam-date-highlight'),true);
    assert.equal(r.node('.time-scroll').scrollTop,690);
    r.run('const previousExamExpiry=examExpiry');
    creationClick(r,{replanExam:'after'});
    assert.equal(r.run('selectedDate'),'2026-12-12');
    assert.equal(r.run('creationWeekKey(dateKey(viewDate))'),'2026-12-06');
    assert.equal(r.run('planCreation.preview.examSpotlight.kind'),'after');
    assert.match(r.node('#app').innerHTML,/exam-date-button replan-exam-date-highlight/);
    r.run('previousExamExpiry()');
    assert.equal(r.run('planCreation.preview.examSpotlight.kind'),'after');
    r.run('examExpiry()');
    assert.equal(r.run('planCreation.preview.examSpotlight'),null);
    assert.equal(r.run('selectedDate'),'2026-12-12');
    assert.equal(r.run('JSON.stringify([plans,events])'),before);
    if(apply){
      creationClick(r,{planCreateAction:'confirm-preview'});
      assert.equal(r.run("plans.find(p=>p.id==='replan').examDate"),'2026-12-12');
      assert.equal(r.run("JSON.stringify(events.filter(e=>e.planId==='replan'&&(e.date<TODAY||e.status==='done')))"),history);
    }else{
      creationClick(r,{planCreateAction:'cancel-preview'});
      assert.equal(r.run('planCreation.examDate'),'2026-12-12');
      creationClick(r,{planCreateAction:'cancel'});
      assert.equal(r.run('JSON.stringify([plans,events])'),before);
    }
    assert.equal(r.node('.phone').classList.contains('creation-preview-active'),false);
  }
});

test('replan emphasis preserves the schedule pulse and animates glow independently',async()=>{
  const r=await remainingReplanRuntime();r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  assert.match(r.node('#app').innerHTML,/replan-default-pulse/);
  const original=r.node('pulse-original'),fresh=r.node('pulse-new'),motions=[];
  original.dataset.replanEventGroup='original';fresh.dataset.replanEventGroup='new';fresh.classList.add('replan-default-pulse');
  r.node('#app').querySelectorAll=selector=>selector==='[data-replan-event-group]'?[original,fresh]:[];
  for(const node of [original,fresh])node.animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  r.run('window.matchMedia=()=>({matches:false})');
  creationClick(r,{replanGroup:'original'});
  assert.equal(fresh.classList.contains('replan-default-pulse'),true);
  assert.equal(motions[1].frames[1].opacity,undefined);
  assert.equal(motions[1].frames[1].filter,undefined);
  r.run('document.handlers.pointerdown({target:{closest:()=>null}})');
  assert.equal(fresh.classList.contains('replan-default-pulse'),true);
  assert.equal(motions.at(-1).frames[1].opacity,undefined);
  assert.equal(motions.at(-1).frames[1].boxShadow,'0 0 0 0px #647d9a00');
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(fresh.classList.contains('replan-default-pulse'),true);
  assert.equal(r.run('replanEmphasisMotions.size'),0);
});

test('exam emphasis fades in and clears on outside clicks without touching schedule blink state or scroll',async()=>{
  const r=await remainingReplanRuntime();r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);creationInput(r,'examDate','2026-12-03');creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  const marker=r.node('exam-emphasis-marker'),date=r.node('exam-emphasis-date'),dayNumber=r.node('exam-emphasis-number'),button=r.node('exam-emphasis-button'),motions=[];
  marker.classList.add('exam-day-tag');date.classList.add('time-day-heading');dayNumber.tagName='b';date.querySelector=()=>dayNumber;
  button.dataset.replanExam='before';button.pressed=false;button.setAttribute=(name,value)=>{if(name==='aria-pressed')button.pressed=value==='true';};
  const previousQuery=r.node('#app').querySelector;
  r.node('#app').querySelector=selector=>selector==='[data-open-day="2026-12-02"]'?date:selector==='[data-replan-exam-marker="before"]'?marker:previousQuery(selector);
  r.node('#app').querySelectorAll=selector=>selector==='.replan-exam-date-highlight'?[marker,date].filter(node=>node.classList.contains('replan-exam-date-highlight')):selector==='[data-replan-exam]'?[button]:selector==='[data-replan-exam][aria-pressed="true"]'&&button.pressed?[button]:[];
  for(const node of [marker,dayNumber,button])node.animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  r.run("window.matchMedia=()=>({matches:false});viewDate=parseDate('2026-12-01')");r.node('.time-scroll').scrollTop=540;
  creationClick(r,{replanExam:'before'});
  assert.equal(r.run('planCreation.preview.examSpotlight.kind'),'before');
  assert.equal(motions.length,3);
  assert.equal(motions.every(m=>m.options.duration===240),true);
  assert.equal(motions[0].frames[0].outlineColor,'transparent');
  assert.equal(motions[0].frames[1].opacity,undefined);
  r.run('document.handlers.pointerdown({target:{closest:()=>null}})');
  assert.equal(r.run('planCreation.preview.examSpotlight'),null);
  assert.equal(marker.classList.contains('replan-exam-date-highlight'),false);
  assert.equal(date.classList.contains('replan-exam-date-highlight'),false);
  assert.equal(motions.length,6);
  assert.equal(motions.at(-1).frames[1].outlineColor,'transparent');
  assert.equal(r.node('.time-scroll').scrollTop,540);
  assert.match(r.node('#app').innerHTML,/replan-default-pulse/);
  motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('replanExamMotions.size'),0);
});

test('final replan confirm and preview cancel fade the preview with its styles intact into the next screen',async()=>{
  for(const apply of [false,true]){
    const r=await remainingReplanRuntime();r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
    creationClick(r,{replanGroup:'original'});
    const app=r.node('#app'),ghost=r.node('final-replan-ghost'),motions=[];
    const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
    app.animate=animate;ghost.animate=animate;app.cloneNode=()=>ghost;
    app.getBoundingClientRect=()=>({left:0,top:52,width:440,height:840});r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
    r.run('window.matchMedia=()=>({matches:false})');
    creationClick(r,{planCreateAction:apply?'confirm-preview':'cancel-preview'});
    assert.equal(motions.length,2);
    assert.equal(ghost.classList.contains('creation-preview-active'),true);
    assert.equal(r.node('.phone').classList.contains('creation-preview-active'),false);
    assert.equal(motions[0].frames[1].opacity,0);
    assert.equal(motions[1].frames[0].opacity,0);
    assert.equal(motions[1].options.duration,300);
    if(apply)assert.equal(r.run('planCreation'),null);
    else assert.equal(r.run('planCreation.preview'),null);
    motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
    assert.equal(ghost.removed,true);
    assert.equal(r.run('detailNavigationMotion'),null);
  }
});

test('remaining-progress replanning previews preferred-time changes, preserves history and applies only on final confirmation', async () => {
  const r=await remainingReplanRuntime(),before=r.run('JSON.stringify([plans,events])'),allocated=r.run("planStatistics('replan').minutes");
  assert.match(r.node('#app').innerHTML,/data-action="replan-remaining"/);
  r.run('openRemainingReplan(selectedPlan)');
  assert.equal(r.run('planCreation.reschedulePlanId'),'replan');
  assert.match(r.node('#app').innerHTML,/남은 학습량 5시간/);
  assert.match(r.node('#app').innerHTML,/data-certificate="engineer"/);
  assert.match(r.node('#app').innerHTML,/<span>정보처리기사 필기<\/span>/);
  editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  assert.ok(r.run('planCreation.preview'));
  assert.match(r.node('#app').innerHTML,/data-certificate="engineer"/);
  assert.match(r.node('#app').innerHTML,/<span>정보처리기사 필기<\/span>/);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  assert.equal(r.run("calendarEvents().filter(e=>e.id==='rp-unknown').length"),1);
  assert.equal(r.run("calendarEvents().find(e=>e.id==='rp-unknown').isCreationPreview"),true);
  assert.equal(r.run('planCreation.preview.events.every(e=>!hasConflict(e))'),true);
  assert.equal(r.run('planCreation.preview.events.reduce((sum,e)=>sum+minutesOfDay(e.end)-minutesOfDay(e.start),0)'),300);
  assert.equal(r.run("planCreation.preview.events.some(e=>e.date==='2026-11-28' && e.start==='11:00')"),true);
  assert.equal(r.run("planCreation.preview.events.some(e=>e.date==='2026-11-26' && e.start<'21:00' && e.end>'20:30')"),false);
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run('planCreation'),null);
  assert.equal(r.run('selectedPlan'),'replan');
  assert.equal(r.run('planStatusFilter'),'partial');
  assert.equal(r.run('planSchedulesExpanded'),true);
  assert.equal(r.run('plans.length'),3);
  assert.equal(r.run("planStatistics('replan').minutes"),allocated+90);
  assert.equal(r.run("events.find(e=>e.id==='rp-past-missed').date"),'2026-11-24');
  assert.equal(r.run("events.find(e=>e.id==='rp-past-partial').progress"),50);
  assert.equal(r.run("events.find(e=>e.id==='rp-done').date"),'2026-11-26');
  assert.equal(r.run("events.filter(e=>e.rescheduleRootId==='rp-partial').reduce((sum,e)=>sum+minutesOfDay(e.end)-minutesOfDay(e.start),0)"),90);
  assert.equal(r.run("events.filter(e=>e.rescheduleRootId==='rp-partial').every(e=>e.progress===25 && e.status==='partial')"),true);
  assert.equal(r.run("plans.find(p=>p.id==='replan').preferences.weekOverrides.extra['2026-11-22'].length"),6);
});

test('cancelling remaining preference editing or its preview leaves all records and saved preferences unchanged', async () => {
  const r=await remainingReplanRuntime(),before=r.run('JSON.stringify([plans,events])');
  r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  creationClick(r,{planCreateAction:'cancel-preview'});
  assert.equal(r.run('planCreation.preview'),null);
  assert.equal(r.run('planCreation.weekExtras[planCreation.preferenceWeek].size'),6);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  creationClick(r,{planCreateAction:'cancel'});
  assert.equal(r.run('planCreation'),null);
  assert.equal(r.run('selectedPlan'),'replan');
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

test('remaining replanning rejects insufficient preferred time without altering any real schedule', async () => {
  const r=await remainingReplanRuntime(),before=r.run('JSON.stringify([plans,events])');
  r.run('openRemainingReplan(selectedPlan)');r.run('planCreation.timeCells.clear()');
  creationClick(r,{planCreateAction:'next'});
  assert.equal(r.run('Boolean(planCreation.preview)'),false);
  assert.match(r.node('#plan-create-error').textContent,/배정 가능한 선호 시간은 0분/);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

test('replanning partial learning repeatedly does not multiply its completion percentage into the remaining volume', async () => {
  const r=await remainingReplanRuntime();r.run('openRemainingReplan(selectedPlan)');
  editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});creationClick(r,{planCreateAction:'confirm-preview'});
  r.run('openRemainingReplan(selectedPlan)');
  assert.equal(r.run("remainingPlanEvents('replan').reduce((sum,e)=>sum+remainingWorkMinutes(e),0)"),300);
  creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  assert.equal(r.run('planCreation.preview.events.reduce((sum,e)=>sum+minutesOfDay(e.end)-minutesOfDay(e.start),0)'),300);
});

test('remaining replanning previews can be dragged and only final confirmation commits the manual adjustment', async () => {
  const r=await remainingReplanRuntime();r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  const before=r.run('JSON.stringify([plans,events])');r.node('#move-preview').hidden=true;
  r.node('source-rp-unknown').getBoundingClientRect=()=>({top:100,height:28});
  r.node('day-2026-11-29').getBoundingClientRect=()=>({top:-800,height:1440});
  r.node('day-2026-11-29').dataset.dropDate='2026-11-29';
  r.run("document.elementFromPoint=()=>({closest:()=>document.querySelector('day-2026-11-29')})");
  r.down('rp-unknown');r.hover('2026-11-29',130);r.up();await r.run('confirmMove()');
  assert.equal(r.run("planCreation.preview.events.find(e=>e.id==='rp-unknown').start"),'15:30');
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run("events.find(e=>e.id==='rp-unknown').date"),'2026-11-29');
  assert.equal(r.run("events.find(e=>e.id==='rp-unknown').start"),'15:30');
});

test('remaining replanning refuses final application if the source learning record changed after preview', async () => {
  const r=await remainingReplanRuntime();r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  const saved=r.run("JSON.stringify(plans.find(p=>p.id==='replan').preferences)");
  r.run("events.find(e=>e.id==='rp-partial').progress=50");creationClick(r,{planCreateAction:'confirm-preview'});
  assert.ok(r.run('planCreation.preview'));
  assert.match(r.node('#toast').textContent,/계획이나 학습 기록이 변경/);
  assert.equal(r.run("JSON.stringify(plans.find(p=>p.id==='replan').preferences)"),saved);
  assert.equal(r.run("events.find(e=>e.id==='rp-unknown').start"),'11:00');
});

test('historical missed and partial learning enables replanning on its own, preserves exact records and never duplicates assigned recovery', async () => {
  const r=await remainingReplanRuntime();
  r.run("events=events.filter(e=>e.planId!=='replan'||e.date<TODAY||e.status==='done');renderPlanDetail()");
  const history=r.run("JSON.stringify(events.filter(e=>e.planId==='replan' && e.date<TODAY))"),before=r.run('JSON.stringify([plans,events])');
  assert.doesNotMatch(r.node('#app').innerHTML,/data-action="replan-remaining" disabled/);
  r.run('openRemainingReplan(selectedPlan)');
  assert.match(r.node('#app').innerHTML,/남은 학습량 1시간 30분/);
  creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  assert.equal(r.run('planCreation.preview.replaceIds.size'),0);
  assert.equal(r.run("planCreation.preview.events.every(e=>e.status==='unknown' && e.progress===0 && e.recoverySourceId && e.date>=TODAY)"),true);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run("JSON.stringify(events.filter(e=>e.planId==='replan' && e.date<TODAY))"),history);
  assert.equal(r.run("events.filter(e=>e.recoverySourceId==='rp-past-missed').reduce((sum,e)=>sum+studyWorkloadMinutes(e),0)"),60);
  assert.equal(r.run("events.filter(e=>e.recoverySourceId==='rp-past-partial').reduce((sum,e)=>sum+studyWorkloadMinutes(e),0)"),30);
  assert.equal(r.run("remainingReplanSources('replan').some(e=>e.historySourceId)"),false);
  r.run("const recovered=events.find(e=>e.recoverySourceId==='rp-past-missed');recovered.status='done';recovered.progress=100");
  assert.equal(r.run("remainingReplanSources('replan').reduce((sum,e)=>sum+remainingWorkMinutes(e),0)"),60);
  r.run('openRemainingReplan(selectedPlan)');creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  assert.equal(r.run('planCreation.preview.events.reduce((sum,e)=>sum+minutesOfDay(e.end)-minutesOfDay(e.start),0)'),60);
  creationClick(r,{planCreateAction:'confirm-preview'});
  assert.equal(r.run("JSON.stringify(events.filter(e=>e.planId==='replan' && e.date<TODAY))"),history);
});

test('a missed recovery that becomes historical is recovered from that attempt without re-adding its original record', async () => {
  const r=await remainingReplanRuntime();
  r.run("events=events.filter(e=>e.planId!=='replan'||e.date<TODAY||e.status==='done');renderPlanDetail()");
  r.run('openRemainingReplan(selectedPlan)');creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});creationClick(r,{planCreateAction:'confirm-preview'});
  const id=r.run("events.find(e=>e.recoverySourceId==='rp-past-partial').id");
  r.run(`const failed=events.find(e=>e.id==='${id}');failed.date='2026-11-24';failed.status='missed';failed.progress=0`);
  assert.equal(r.run("remainingReplanSources('replan').filter(e=>e.historySourceId).length"),1);
  assert.equal(r.run("remainingReplanSources('replan').find(e=>e.historySourceId).historySourceId"),id);
  assert.equal(r.run("remainingReplanSources('replan').reduce((sum,e)=>sum+remainingWorkMinutes(e),0)"),90);
  r.run('openRemainingReplan(selectedPlan)');creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  assert.equal(r.run(`planCreation.preview.events.some(e=>e.recoverySourceId==='${id}')`),true);
  assert.equal(r.run("planCreation.preview.replaceIds.has('rp-past-partial')"),false);
});

test('remaining replan preview reports replaced and proposed schedule counts and explicitly preserves history', async () => {
  const r=await remainingReplanRuntime();r.run('openRemainingReplan(selectedPlan)');editRemainingPreferences(r);creationClick(r,{planCreateAction:'next'});creationClick(r,{planCreateAction:'show-replan-preview'});
  const html=r.node('#app').innerHTML,count=r.run('planCreation.preview.events.length'),newRecovery=r.run('planCreation.preview.newRecoveryIds.size');
  assert.match(html,/대체할 기존 일정 <strong>3개/);
  assert.match(html,new RegExp(`새로 배정할 일정 <strong>${count}개`));
  assert.match(html,new RegExp(`과거 보충 일정은 ${newRecovery}개`));
  assert.match(html,/과거 기록과 완료 일정은 그대로 유지됩니다/);
  assert.match(html,/마지막 확인에서만 반영/);
});

function dragMonthAgenda(r,from,to,cancel=false){
  const handle={dataset:{agendaDrag:'true'}};
  r.node('#app').handlers.pointerdown({button:0,pointerId:771,clientY:from,preventDefault(){},target:{closest:selector=>selector==='[data-agenda-drag]'?handle:null}});
  r.run(`document.handlers.pointermove({pointerId:771,clientY:${to},preventDefault(){}})`);
  r.run(cancel?'document.handlers.pointercancel({pointerId:771})':`document.handlers.pointerup({pointerId:771,clientY:${to}})`);
}

test('normal month agenda drags to half the phone height, shows the selected day details, and folds back without changing records', () => {
  const r=runtime(),before=r.run('JSON.stringify([plans,events])');
  r.node('.agenda').getBoundingClientRect=()=>({height:parseFloat(r.node('.phone').style['--month-agenda-height'])});
  r.node('.grid').getBoundingClientRect=()=>({height:parseFloat(r.node('.phone').style['--month-agenda-grid-height'])});
  dragMonthAgenda(r,650,350);
  assert.equal(r.run('monthAgendaExpanded'),true);
  assert.equal(r.node('.phone').style['--month-agenda-height'],'446px');
  assert.equal(r.node('.phone').classList.contains('month-agenda-expanded'),true);
  assert.equal(r.node('#app').captured,null);
  assert.equal(r.run('pointerMove'),null);
  r.choose('2026-11-27');
  assert.equal(r.run('selectedDate'),'2026-11-27');
  assert.equal(r.run('monthAgendaExpanded'),true);
  assert.match(r.node('#app').innerHTML,/27일 금요일/);
  assert.match(r.node('#app').innerHTML,/agenda-extra/);
  dragMonthAgenda(r,350,650);
  assert.equal(r.run('monthAgendaExpanded'),false);
  dragMonthAgenda(r,650,350,true);
  assert.equal(r.run('monthAgendaExpanded'),false);
  assert.equal(r.run('JSON.stringify([plans,events])'),before);
});

test('selecting a month date expands its agenda and shrinks the calendar with synchronized animations', async () => {
  const r=runtime();r.run('window.matchMedia=()=>({matches:false})');
  const animations=[];
  for(const selector of ['.agenda','.grid']){
    const node=r.node(selector);node.getBoundingClientRect=()=>({height:selector==='.agenda'?165:560});
    node.animate=(frames,options)=>{let resolve;const animation={finished:new Promise(done=>{resolve=done;}),cancel(){},finish(){resolve();}};animations.push({frames,options,animation});return animation;};
  }
  const before=r.run('JSON.stringify(events)');
  r.choose('2026-11-27');
  assert.equal(r.run('selectedDate'),'2026-11-27');
  assert.equal(r.run('monthAgendaExpanded'),true);
  assert.match(r.node('#app').innerHTML,/27일 금요일/);
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.equal(animations.length,2);
  assert.equal(animations[0].frames.at(-1).height,'446px');
  assert.equal(animations[1].frames.at(-1).height,'267px');
  assert.equal(animations.every(item=>item.options.duration===320),true);
  animations.forEach(item=>item.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('monthAgendaMotion'),null);
  r.node('.agenda').getBoundingClientRect=()=>({height:446});
  r.node('.grid').getBoundingClientRect=()=>({height:267});
  const grid=r.node('.grid');grid.classList.add('grid');
  r.node('#app').handlers.click({target:{closest:selector=>selector==='.grid'?grid:null}});
  assert.equal(r.run('monthAgendaExpanded'),false);
  assert.equal(r.run('selectedDate'),'2026-11-27');
  assert.equal(animations.length,4);
  assert.equal(animations[2].frames[0].height,'446px');
  assert.equal(animations[2].frames[1].height,'153px');
  assert.equal(animations[3].frames[0].height,'267px');
  assert.equal(animations[3].frames[1].height,'560px');
  animations.slice(2).forEach(item=>item.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(r.run('monthAgendaMotion'),null);
});

test('switching month dates crossfades agenda content without folding and cleans up rapid selections',async()=>{
  const r=runtime();r.run('setMonthAgendaExpanded(true);window.matchMedia=()=>({matches:false})');
  const motions=[],ghosts=[];
  const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){this.cancelled=true;finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
  r.node('.agenda').getBoundingClientRect=()=>({left:10,top:400,width:400,height:446});
  for(const selector of ['.agenda-date','.agenda-list']){
    const node=r.node(selector);node.getBoundingClientRect=()=>({left:selector==='.agenda-date'?30:150,top:435,width:selector==='.agenda-date'?100:240,height:330});
    node.animate=animate;node.cloneNode=()=>{const ghost=r.node(`agenda-fade-${ghosts.length}`);ghost.animate=animate;ghosts.push(ghost);return ghost;};
  }
  r.node('.agenda-list').scrollTop=132;
  r.choose('2026-11-27');
  assert.equal(r.run('monthAgendaExpanded'),true);
  assert.equal(r.node('.phone').style['--month-agenda-height'],'446px');
  assert.equal(motions.length,4);
  assert.equal(motions[0].frames[1].opacity,0);
  assert.equal(motions[1].frames[0].opacity,0);
  assert.equal(motions.every(motion=>motion.options.duration===240),true);
  assert.equal(ghosts[1].scrollTop,132);
  assert.equal(ghosts.every(ghost=>ghost.inert),true);
  assert.equal(r.node('.agenda-list').scrollTop,0);
  r.choose('2026-11-28');
  assert.equal(motions.length,8);
  assert.equal(motions.slice(0,4).every(motion=>motion.animation.cancelled),true);
  assert.equal(ghosts.slice(0,2).every(ghost=>ghost.removed),true);
  assert.equal(r.run('selectedDate'),'2026-11-28');
  assert.match(r.node('#app').innerHTML,/28일 토요일/);
  assert.equal(r.run('monthAgendaExpanded'),true);
  motions.forEach(motion=>motion.animation.finish());await new Promise(resolve=>setImmediate(resolve));
  assert.equal(ghosts.every(ghost=>ghost.removed),true);
  assert.equal(r.run('monthAgendaContentMotion'),null);
});

test('selecting an empty month date also opens the agenda when motion is reduced', () => {
  const r=runtime();
  for(const selector of ['.agenda-date','.agenda-list'])r.node(selector).animate=()=>{throw new Error('reduced motion should not animate agenda content');};
  r.run('events=[];renderCalendar()');
  r.choose('2026-11-28');
  assert.equal(r.run('monthAgendaExpanded'),true);
  assert.equal(r.node('.phone').style['--month-agenda-height'],'446px');
  assert.match(r.node('#app').innerHTML,/28일 토요일/);
  assert.match(r.node('#app').innerHTML,/일정이 없습니다/);
  assert.equal(r.run('monthAgendaMotion'),null);
});

test('behind-schedule records activate a plan corner dot and menu dot even without calendar conflicts', () => {
  const r=runtime();
  r.run("events=[{id:'overdue',planId:'engineer',type:'study',date:'2026-11-24',start:'09:00',end:'10:00',title:'지난 학습',content:'',status:'unknown',progress:0}];renderCalendar();renderPlanList()");
  for(const status of ['partial','missed','unknown']){
    r.run(`events[0].status='${status}';renderCalendar();renderPlanList()`);
    assert.equal(r.run('calendarConflicts().length'),0);
    assert.match(r.node('#app').innerHTML,/plans-conflict-dot/);
    assert.equal((r.node('#plans-list').innerHTML.match(/class="plan-overdue-dot"/g)||[]).length,1);
    assert.equal(r.run("pastUnfinishedEvents('sqld').length"),0);
  }
  r.run("setPlanActive('engineer',false)");
  assert.match(r.node('#app').innerHTML,/plans-conflict-dot/);
  r.run("events[0].status='done';renderCalendar();renderPlanList()");
  assert.doesNotMatch(r.node('#app').innerHTML,/plans-conflict-dot/);
  assert.doesNotMatch(r.node('#plans-list').innerHTML,/class="plan-overdue-dot"/);
  r.run("events[0].status='unknown';events[0].date=TODAY;renderCalendar()");
  assert.match(r.node('#app').innerHTML,/plans-conflict-dot/);
});

test('sidebar plans with a progress warning open normal details without a past-only schedule list',async()=>{
  for(const active of [true,false]){
    const r=runtime();
    r.run(`events=[{id:'past',date:'2026-11-24',status:'unknown'},{id:'future',date:'2026-11-26',status:'unknown'}].map(e=>({...e,planId:'engineer',type:'study',title:e.id,content:'',start:'09:00',end:'10:00'}));
      plans.find(p=>p.id==='engineer').active=${active};renderPlanList()`);
    await r.node('#plans-list').handlers.click({target:{closest:selector=>selector==='[data-open-plan]'?{dataset:{openPlan:'engineer'}}:null}});
    assert.equal(r.run('planSchedulesExpanded'),false);
    assert.doesNotMatch(r.node('#app').innerHTML,/지난 미완료·미확인 일정|clear-past-filter/);
    assert.match(r.node('#app').innerHTML,/전체 학습 일정/);
    creationClick(r,{action:'toggle-plan-schedules'});
    assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,2);
    creationClick(r,{locateEvent:'past'});creationClick(r,{action:'return-topic'});
    assert.equal(r.run('planSchedulesExpanded'),true);
    assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,2);
  }
});

test('a sidebar plan without past unfinished records still opens with all schedules collapsed', async () => {
  const r=runtime();r.run("events=events.filter(e=>e.planId!=='engineer'||e.status==='done'||e.date>=TODAY)");
  await r.node('#plans-list').handlers.click({target:{closest:selector=>selector==='[data-open-plan]'?{dataset:{openPlan:'engineer'}}:null}});
  assert.equal(r.run('planSchedulesExpanded'),false);
  assert.match(r.node('#app').innerHTML,/aria-expanded="false" aria-controls="plan-schedules-panel"/);
});

test('plan details replace past unfinished share with expected progress while preserving replan warnings', async () => {
  const r=runtime();
  r.run(`events=[
    {id:'share-partial',date:'2026-11-24',status:'partial'},
    {id:'share-missed',date:'2026-11-23',status:'missed'},
    {id:'share-unknown',date:'2026-11-22',status:'unknown'},
    {id:'share-done',date:'2026-11-21',status:'done'},
    {id:'share-today',date:TODAY,status:'unknown'},
    {id:'share-future',date:'2026-11-26',status:'partial'}
  ].map(e=>({...e,planId:'engineer',type:'study',title:e.id,content:'',start:'09:00',end:'10:00',progress:50}));
  events.push({id:'share-other-plan',planId:'sqld',type:'study',title:'다른 계획',date:'2026-11-24',start:'09:00',end:'10:00',status:'missed'})`);
  await r.node('#plans-list').handlers.click({target:{closest:selector=>selector==='[data-open-plan]'?{dataset:{openPlan:'engineer'}}:null}});
  const html=r.node('#app').innerHTML;
  assert.doesNotMatch(html,/past-progress-overview|지난 미완료·미확인 일정 비중/);
  assert.match(html,/예상 학습 진행률 83퍼센트/);
  assert.match(html,/class="plan-progress-track" style="--expected-progress:83%"><progress[^>]*aria-label="전체 학습 진행률/);
  assert.match(html,/class="plan-progress-reference" role="img" aria-label="예상 학습 진행률 83퍼센트 기준선"/);
  assert.doesNotMatch(html,/<progress class="expected-progress"/);
  assert.match(html,/class="replan-remaining-button active"/);
  assert.ok(html.indexOf('data-action="replan-remaining"')>html.indexOf('class="detail-heading"'));
  assert.ok(html.indexOf('data-action="replan-remaining"')<html.indexOf('class="card plan-overview"'));
  assert.ok(html.indexOf('data-action="replan-remaining"')<html.indexOf('class="section-heading plan-schedule-heading"'));
  assert.ok(html.indexOf('data-action="replan-remaining"')<html.indexOf('학습 주제별 시간 배정'));
  creationClick(r,{topicStatus:'all'});
  assert.match(r.node('#app').innerHTML,/plan-status-detail/);
  creationClick(r,{action:'plan-section-back'});
  assert.match(r.node('#app').innerHTML,/예상 학습 진행률 83퍼센트/);
  r.run("events.forEach(e=>{e.status='done';e.progress=100});renderPlanDetail()");
  assert.doesNotMatch(r.node('#app').innerHTML,/replan-remaining-button active/);
  assert.match(r.node('#app').innerHTML,/data-action="replan-remaining" disabled/);
});

test('expected progress weights scheduled workload through today independently of recorded completion',async()=>{
  const r=runtime();
  r.run(`events=[
    {id:'past',date:'2026-11-24',start:'09:00',end:'10:00',status:'missed'},
    {id:'today',date:TODAY,start:'09:00',end:'09:30',workloadMinutes:120,status:'unknown'},
    {id:'future',date:'2026-11-26',start:'09:00',end:'12:00',status:'done'}
  ].map(e=>({...e,planId:'engineer',type:'study',title:e.id,content:'',progress:0}));
  events.push({id:'other',planId:'sqld',type:'study',date:TODAY,start:'00:00',end:'10:00',status:'done'});
  events.push({id:'calendar',planId:'engineer',type:'calendar',date:TODAY,start:'00:00',end:'10:00',status:'done'})`);
  assert.equal(r.run("planStatistics('engineer').expectedMinutes"),180);
  assert.equal(r.run("planStatistics('engineer').expectedProgress"),50);
  await r.run("openPlanDetail('engineer')");
  assert.match(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/예상 학습 진행률 50퍼센트/);
  assert.doesNotMatch(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/plan-progress-lag|plan-progress-lead/);
  assert.match(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/<p class="plan-progress-on-track" role="status">계획대로 진행하고 있어요\.<\/p>/);
  r.run("events.filter(e=>e.planId==='engineer' && e.type==='study').forEach(e=>e.status='unknown');renderPlanDetail()");
  assert.match(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/<p class="plan-progress-lag" role="status">예상보다 50%p 뒤처져 있어요\.<\/p>/);
  assert.doesNotMatch(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/plan-progress-on-track/);
  assert.doesNotMatch(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/plan-progress-lead/);
  r.run("events.filter(e=>e.planId==='engineer' && e.type==='study').forEach(e=>e.status='done');renderPlanDetail()");
  assert.equal(r.run("planStatistics('engineer').progress"),100);
  assert.match(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/<p class="plan-progress-lead" role="status">예상보다 50%p 앞서 있어요\.<\/p>/);
  assert.doesNotMatch(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/plan-progress-on-track/);
  assert.doesNotMatch(r.node('#app').innerHTML.split('<section class="card topic-status-card plan-status-overview">')[0],/plan-progress-lag/);
  assert.equal(r.run("planStatistics('engineer').expectedProgress"),50);
  r.run("events.filter(e=>e.planId==='engineer' && e.type==='study').forEach(e=>e.date='2026-11-26')");
  assert.equal(r.run("planStatistics('engineer').expectedProgress"),0);
  r.run("events.filter(e=>e.planId==='engineer' && e.type==='study').forEach(e=>e.date=TODAY)");
  assert.equal(r.run("planStatistics('engineer').expectedProgress"),100);
});

test('expected progress handles an empty plan without invalid ratios or an active replan action', async () => {
  const r=runtime();r.run("events=events.filter(e=>e.planId!=='engineer')");await r.run("openPlanDetail('engineer')");
  assert.match(r.node('#app').innerHTML,/예상 학습 진행률 0퍼센트/);
  assert.match(r.node('#app').innerHTML,/class="plan-progress-track" style="--expected-progress:0%"/);
  assert.doesNotMatch(r.node('#app').innerHTML,/NaN|Infinity|replan-remaining-button active/);
});

test('replan warning emphasis and plan dot use actual versus expected progress', async () => {
  const cases=[['partial','2026-11-24',true],['missed','2026-11-24',true],['unknown','2026-11-24',true],['done','2026-11-24',false],['unknown','2026-11-25',true],['partial','2026-11-26',false]];
  for(const [status,date,warning] of cases){
    const r=runtime();r.run(`events=[{id:'warning-source',planId:'engineer',type:'study',date:'${date}',start:'09:00',end:'10:00',title:'예시 학습',content:'',status:'${status}',progress:50}];plans.find(p=>p.id==='engineer').active=false;renderPlanList()`);
    assert.equal(r.node('#plans-list').innerHTML.includes('class="plan-overdue-dot"'),warning);
    await r.run("openPlanDetail('engineer')");
    assert.equal(r.node('#app').innerHTML.includes('replan-remaining-button active'),warning);
  }
});

test('progress warnings clear when ahead despite past unfinished records and enable for today when behind',async()=>{
  const r=runtime();
  r.run(`events=[
    {id:'leftover',date:'2026-11-24',status:'missed',start:'09:00',end:'10:00'},
    {id:'ahead',date:'2026-11-26',status:'done',start:'09:00',end:'12:00'}
  ].map(e=>({...e,planId:'engineer',type:'study',title:e.id,content:''}));renderPlanList();renderCalendar()`);
  assert.equal(r.run("pastUnfinishedEvents('engineer').length"),1);
  assert.equal(r.run("isPlanBehind('engineer')"),false);
  assert.doesNotMatch(r.node('#plans-list').innerHTML,/plan-overdue-dot/);
  assert.doesNotMatch(r.node('#app').innerHTML,/plans-conflict-dot/);
  await r.run("openPlanDetail('engineer')");
  assert.doesNotMatch(r.node('#app').innerHTML,/replan-remaining-button active/);
  r.run("events.find(e=>e.id==='ahead').status='unknown';events.find(e=>e.id==='leftover').date=TODAY;renderPlanList();renderPlanDetail()");
  assert.equal(r.run("pastUnfinishedEvents('engineer').length"),0);
  assert.match(r.node('#plans-list').innerHTML,/plan-overdue-dot/);
  assert.match(r.node('#app').innerHTML,/replan-remaining-button active/);
});

test('graph topic navigation restores the original screen, filters and scroll after button or Escape return',async()=>{
  for(const overview of [false,true])for(const escape of [false,true]){
    const r=runtime();await r.run("openPlanDetail('engineer')");
    r.run("planStatusFilter='done';planSchedulesExpanded=true;renderPlanDetail()");
    if(overview)creationClick(r,{action:'topic-allocation-details'});
    const original=r.node('#app').innerHTML;
    r.node('.detail-scroll').scrollTop=overview?432:628;
    r.node('.plan-schedule-list').scrollTop=94;
    creationClick(r,{planTopic:'모의 시험'});
    assert.equal(r.node('.detail-scroll').scrollTop,0);
    r.node('.detail-scroll').scrollTop=81;
    creationClick(r,{locateEvent:'sample-19'});
    creationClick(r,{action:'return-topic'});
    assert.equal(r.run('selectedTopic'),'모의 시험');
    if(escape)r.node('#app').handlers.keydown({key:'Escape',target:{closest:()=>null},preventDefault(){}});
    else creationClick(r,{action:'topic-back'});
    assert.equal(r.run('selectedTopic'),null);
    assert.equal(r.run('planTopicsOverview'),overview);
    assert.equal(r.node('.detail-scroll').scrollTop,overview?432:628);
    assert.equal(r.node('.plan-schedule-list').scrollTop,94);
    assert.equal(r.run('planStatusFilter'),'done');
    assert.equal(r.run('planSchedulesExpanded'),true);
    assert.equal(r.node('#app').innerHTML,original);
  }
});

test('graph navigation fades and slides both screens, preserves scroll and cleans up interrupted transitions',async()=>{
  for(const route of ['status','topic','allocation']){
    const r=runtime();await r.run("openPlanDetail('engineer')");
    r.run('window.matchMedia=()=>({matches:false})');
    const detail=r.node('.detail'),motions=[],ghosts=[];
    const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){this.cancelled=true;finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
    detail.getBoundingClientRect=()=>({left:10,top:70,width:420,height:800});
    r.node('.phone').getBoundingClientRect=()=>({left:10,top:0});
    detail.animate=animate;
    detail.cloneNode=()=>{const index=ghosts.length,ghost=r.node(`nav-ghost-${index}`);ghost.animate=animate;ghost.querySelector=selector=>r.node('ghost-'+index+selector);ghosts.push(ghost);return ghost;};
    r.node('.detail-scroll').scrollTop=520;
    if(route==='status')creationClick(r,{topicStatus:'unknown'});
    else if(route==='topic')creationClick(r,{planTopic:'모의 시험'});
    else creationClick(r,{action:'topic-allocation-details'});
    assert.equal(motions.length,2);
    assert.equal(ghosts[0].inert,true);
    assert.equal(r.node('ghost-0.detail-scroll').scrollTop,520);
    assert.equal(r.node('.detail-scroll').scrollTop,0);
    assert.equal(motions[1].frames[0].transform,'translateX(32px)');
    assert.equal(motions[0].frames[1].opacity,0);
    assert.equal(motions[0].options.duration,300);
    creationClick(r,{action:route==='status'?'plan-section-back':route==='topic'?'topic-back':'allocation-back'});
    assert.equal(ghosts[0].removed,true);
    assert.equal(motions.slice(0,2).every(m=>m.animation.cancelled),true);
    assert.equal(motions[3].frames[0].transform,'translateX(-32px)');
    assert.equal(r.node('.detail-scroll').scrollTop,520);
    motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
    assert.equal(ghosts[1].removed,true);
    assert.equal(r.run('detailNavigationMotion'),null);
  }
});

test('sidebar plan entry waits for drawer closing and animates both ways while restoring the calendar',async()=>{
  for(const view of ['month','week']){
    const r=runtime();r.run(`calendarView='${view}';renderCalendar();window.matchMedia=()=>({matches:false})`);
    const pageMotions=[],drawerMotions=[],ghosts=[],app=r.node('#app');
    const animate=(collection)=>(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){this.cancelled=true;finish();},finish(){finish();}};collection.push({frames,options,animation});return animation;};
    app.animate=animate(pageMotions);
    app.getBoundingClientRect=()=>({left:10,top:52,width:420,height:840});
    r.node('.phone').getBoundingClientRect=()=>({left:10,top:0});
    app.cloneNode=()=>{const index=ghosts.length,ghost=r.node(`calendar-nav-ghost-${index}`);ghost.innerHTML=app.innerHTML;ghost.animate=animate(pageMotions);ghost.querySelector=selector=>r.node(`calendar-ghost-${index}${selector}`);ghosts.push(ghost);return ghost;};
    r.node('#plans-dialog').animate=animate(drawerMotions);r.node('#plans-backdrop').animate=animate(drawerMotions);
    r.node('.time-scroll').scrollTop=620;r.node('.agenda-list').scrollTop=55;
    r.run('openPlansDrawer()');
    const opening=r.node('#plans-list').handlers.click({target:{closest:s=>s==='[data-open-plan]'?{dataset:{openPlan:'engineer'}}:null}});
    assert.equal(drawerMotions.length,2);
    assert.equal(pageMotions.length,0);
    assert.equal(r.run('selectedPlan'),null);
    drawerMotions.forEach(m=>m.animation.finish());await opening;
    assert.equal(r.node('#plans-drawer').hidden,true);
    assert.equal(app.inert,false);
    assert.equal(r.run('selectedPlan'),'engineer');
    assert.equal(pageMotions.length,2);
    assert.equal(pageMotions[1].frames[0].transform,'translateX(32px)');
    assert.equal(r.node('calendar-ghost-0.time-scroll').scrollTop,620);
    creationClick(r,{action:'plan-back'});
    assert.equal(r.run('selectedPlan'),null);
    assert.equal(r.run('calendarView'),view);
    assert.equal(r.run('selectedDate'),'2026-11-25');
    assert.equal(r.node('.time-scroll').scrollTop,620);
    assert.equal(r.node('.agenda-list').scrollTop,55);
    assert.equal(pageMotions[3].frames[0].transform,'translateX(-32px)');
    assert.equal(ghosts[0].removed,true);
    pageMotions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
    assert.equal(ghosts[1].removed,true);
    assert.equal(r.run('detailNavigationMotion'),null);
  }
});

test('calendar event details animate both ways and restore the original view, date and scroll after recording progress',async()=>{
  for(const view of ['month','week','day'])for(const escape of [false,true]){
    const r=runtime();r.run(`calendarView='${view}';selectedDate='${view==='day'?'2026-11-26':'2026-11-25'}';viewDate=parseDate(selectedDate);monthAgendaExpanded=true;renderCalendar();window.matchMedia=()=>({matches:false})`);
    const original=r.run('JSON.stringify([calendarView,selectedDate,dateKey(viewDate),monthAgendaExpanded])');
    const app=r.node('#app'),motions=[],ghosts=[];
    const animate=(frames,options)=>{let finish;const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){finish();},finish(){finish();}};motions.push({frames,options,animation});return animation;};
    app.animate=animate;app.getBoundingClientRect=()=>({left:0,top:52,width:440,height:840});
    r.node('.phone').getBoundingClientRect=()=>({left:0,top:0});
    app.cloneNode=()=>{const index=ghosts.length,ghost=r.node(`event-ghost-${index}`);ghost.animate=animate;ghost.querySelector=selector=>r.node(`event-ghost-scroll-${index}${selector}`);ghosts.push(ghost);return ghost;};
    r.node('.time-scroll').scrollTop=763;r.node('.agenda-list').scrollTop=92;
    creationClick(r,{event:'sample-19'});
    assert.equal(r.run('selectedEvent'),'sample-19');
    assert.equal(motions[1].frames[0].transform,'translateX(32px)');
    assert.equal(r.node('event-ghost-scroll-0.time-scroll').scrollTop,763);
    creationClick(r,{status:'done'});
    if(escape)r.node('#app').handlers.keydown({key:'Escape',target:{closest:()=>null},preventDefault(){}});
    else creationClick(r,{action:'back'});
    assert.equal(r.run('selectedEvent'),null);
    assert.equal(r.run('JSON.stringify([calendarView,selectedDate,dateKey(viewDate),monthAgendaExpanded])'),original);
    assert.equal(r.node('.time-scroll').scrollTop,763);
    assert.equal(r.node('.agenda-list').scrollTop,92);
    assert.equal(r.run("events.find(e=>e.id==='sample-19').status"),'done');
    assert.equal(motions[3].frames[0].transform,'translateX(-32px)');
    motions.forEach(m=>m.animation.finish());await new Promise(resolve=>setImmediate(resolve));
    assert.equal(ghosts.every(ghost=>ghost.removed),true);
    assert.equal(r.run('detailNavigationMotion'),null);
  }
});

test('returning from an event opened in search restores the query and result scroll',()=>{
  const r=runtime();
  r.run("searchOpen=true;renderCalendar()");r.node('#search-input').value='모의';r.run("renderSearch('모의')");
  r.node('.search-results').scrollTop=245;
  creationClick(r,{event:'sample-19'});
  r.node('#search-input').value='';
  creationClick(r,{action:'back'});
  assert.equal(r.run('searchOpen'),true);
  assert.equal(r.node('#search-input').value,'모의');
  assert.equal(r.node('.search-results').scrollTop,245);
  assert.match(r.node('#search-results').innerHTML,/모의 시험/);
  assert.doesNotMatch(r.node('#search-results').innerHTML,/오답 정리/);
});

test('graph navigation skips motion when reduced motion is requested',async()=>{
  const r=runtime();await r.run("openPlanDetail('engineer')");
  r.node('.detail').cloneNode=()=>{throw new Error('reduced motion should not clone a screen');};
  r.node('.detail').animate=()=>{throw new Error('reduced motion should not animate');};
  creationClick(r,{planTopic:'모의 시험'});
  assert.equal(r.run('selectedTopic'),'모의 시험');
  creationClick(r,{action:'topic-back'});
  assert.equal(r.run('selectedTopic'),null);
  assert.equal(r.run('detailNavigationMotion'),null);
});

test('main plan state legends and pie slices open status details with exactly the chosen filter',async()=>{
  for(const mode of ['legend','slice','keyboard'])for(const status of ['done','partial','missed','unknown']){
    const r=runtime();
    r.run(`events=['done','partial','missed','unknown'].map((state,index)=>({id:'route-'+state,planId:'engineer',type:'study',topic:'학습',title:state,date:'2026-11-'+(26+index),start:'09:00',end:'10:00',status:state,progress:50,content:''}))`);
    await r.run("openPlanDetail('engineer')");
    r.node('.detail-scroll').scrollTop=416;
    if(mode==='legend')creationClick(r,{topicStatus:status});
    else{
      const slice={dataset:{topicStatus:status},tagName:'path'};
      if(mode==='slice')r.node('#app').handlers.click({target:{closest:selector=>selector==='[data-topic-status]'?slice:null}});
      else r.node('#app').handlers.keydown({key:'Enter',preventDefault(){},target:{closest:selector=>selector==='[data-topic-status]'?slice:null}});
    }
    assert.equal(r.run('planStatusOverview'),true);
    assert.equal(r.run('planStatusFilter'),status);
    assert.equal(r.node('.detail-scroll').scrollTop,0);
    assert.match(r.node('#app').innerHTML,/plan-status-detail/);
    assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,1);
    assert.ok(r.node('#app').innerHTML.includes(`data-locate-event="route-${status}"`));
    creationClick(r,{topicStatus:status});
    assert.equal(r.run('planStatusFilter'),null);
    assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,4);
    creationClick(r,{action:'plan-section-back'});
    assert.equal(r.run('planStatusOverview'),false);
    assert.equal(r.node('.detail-scroll').scrollTop,416);
    assert.equal(r.run('planStatusFilter'),null);
    assert.equal(r.run('planSchedulesExpanded'),false);
    assert.equal((r.node('#app').innerHTML.match(/data-locate-event=/g)||[]).length,4);
    assert.match(r.node('#app').innerHTML,/전체 학습 일정/);
    assert.doesNotMatch(r.node('#app').innerHTML,/class="topic-chart-slice selected"/);
  }
});

test('both plan detail links restore the original scroll and status details support filtering and calendar return',async()=>{
  for(const kind of ['topics','status'])for(const escape of [false,true]){
    const r=runtime();await r.run("openPlanDetail('engineer')");
    r.run("planStatusFilter='done';planSchedulesExpanded=true;renderPlanDetail()");
    r.node('.detail-scroll').scrollTop=517;r.node('.plan-schedule-list').scrollTop=71;
    const original=r.node('#app').innerHTML;
    assert.match(original,/data-action="plan-status-details">자세히 보기/);
    creationClick(r,{action:kind==='topics'?'topic-allocation-details':'plan-status-details'});
    assert.equal(r.node('.detail-scroll').scrollTop,0);
    assert.match(r.node('#app').innerHTML,/class="plan-target-certificate">[\s\S]*data-certificate="engineer"/);
    assert.match(r.node('#app').innerHTML,/<span>정보처리기사 필기<\/span>/);
    if(kind==='status'){
      assert.match(r.node('#app').innerHTML,/plan-status-detail/);
      assert.equal(r.run('planSchedulesExpanded'),true);
      assert.doesNotMatch(r.node('#app').innerHTML,/data-action="plan-status-details"/);
      creationClick(r,{topicStatus:'unknown'});
      assert.match(r.node('#app').innerHTML,/data-locate-event="sample-19"/);
      creationClick(r,{locateEvent:'sample-19'});creationClick(r,{action:'return-topic'});
      assert.match(r.node('#app').innerHTML,/plan-status-detail/);
      assert.equal(r.run('planStatusFilter'),'unknown');
    }else{
      r.node('.detail-scroll').scrollTop=294;
      creationClick(r,{planTopic:'모의 시험'});creationClick(r,{action:'topic-back'});
      assert.equal(r.node('.detail-scroll').scrollTop,294);
    }
    r.node('.detail-scroll').scrollTop=42;
    if(escape)r.node('#app').handlers.keydown({key:'Escape',target:{closest:()=>null},preventDefault(){}});
    else creationClick(r,{action:kind==='topics'?'allocation-back':'plan-section-back'});
    assert.equal(r.node('.detail-scroll').scrollTop,517);
    assert.equal(r.node('.plan-schedule-list').scrollTop,71);
    assert.equal(r.run('planStatusFilter'),'done');
    assert.equal(r.run('planTopicsOverview || planStatusOverview'),false);
    assert.equal(r.node('#app').innerHTML,original);
  }
});

test('allocation overview shows weighted topic hours, links to cards and preserves its return route',async()=>{
  const r=runtime();
  r.run(`events=[
    {id:'allocation-a',topic:'데이터베이스',workloadMinutes:120,date:TODAY},
    {id:'allocation-b',topic:'알고리즘',date:'2026-11-26'}
  ].map(e=>({...e,planId:'engineer',type:'study',title:e.id,content:'',status:'unknown',start:'09:00',end:'10:00'}));
  events.push({id:'other-allocation',planId:'sqld',type:'study',topic:'다른 계획',title:'다른 계획',date:TODAY,status:'done',start:'09:00',end:'12:00'})`);
  await r.run("openPlanDetail('engineer')");
  let html=r.node('#app').innerHTML;
  assert.match(html,/allocation-heading/);
  assert.match(html,/data-action="topic-allocation-details">자세히 보기/);
  assert.match(html,/2시간 <small>· 67%/);
  assert.match(html,/1시간 <small>· 33%/);
  const arcs=[...html.matchAll(/class="allocation-donut-slice"[^>]*stroke-dasharray="([\d.]+) ([\d.]+)"/g)];
  assert.equal(arcs.length,2);
  assert.ok(Math.abs(Number(arcs[0][1])/Number(arcs[0][2])-2/3)<1e-9);
  assert.ok(Math.abs(Number(arcs[1][1])/Number(arcs[1][2])-1/3)<1e-9);
  assert.doesNotMatch(html,/plan-topic-list|지난 미완료·미확인 일정/);
  assert.ok(html.indexOf('class="section-heading plan-schedule-heading"')>html.indexOf('class="card topic-allocation"'));
  assert.match(html,/<button type="button" class="allocation-row" data-plan-topic="데이터베이스"/);
  assert.match(html,/class="allocation-chart" role="group"/);
  creationClick(r,{planTopic:'데이터베이스'});
  assert.equal(r.run('selectedTopic'),'데이터베이스');
  assert.match(r.node('#app').innerHTML,/학습 주제 상세/);
  creationClick(r,{action:'topic-back'});
  assert.equal(r.run('planTopicsOverview'),false);
  assert.match(r.node('#app').innerHTML,/data-action="topic-allocation-details"/);
  const circle={dataset:{planTopic:'알고리즘'},tagName:'circle'};
  r.node('#app').handlers.keydown({key:' ',preventDefault(){},target:{closest:selector=>selector==='[data-plan-topic]'?circle:null}});
  assert.equal(r.run('selectedTopic'),'알고리즘');
  creationClick(r,{action:'topic-back'});
  const records=r.run('JSON.stringify(events)');
  creationClick(r,{action:'topic-allocation-details'});
  assert.match(r.node('#app').innerHTML,/plan-allocation-detail/);
  assert.match(r.node('#app').innerHTML,/plan-topic-list/);
  assert.match(r.node('#app').innerHTML,/allocation-chart/);
  creationClick(r,{planTopic:'알고리즘'});
  creationClick(r,{locateEvent:'allocation-b'});
  assert.equal(r.run('calendarView'),'week');
  creationClick(r,{action:'return-topic'});
  creationClick(r,{action:'topic-back'});
  assert.match(r.node('#app').innerHTML,/plan-allocation-detail/);
  creationClick(r,{action:'allocation-back'});
  assert.equal(r.run('selectedPlan'),'engineer');
  assert.doesNotMatch(r.node('#app').innerHTML,/plan-allocation-detail|plan-topic-list/);
  assert.equal(r.run('JSON.stringify(events)'),records);
  r.run("events=events.filter(e=>e.planId!=='engineer');renderPlanDetail()");
  assert.doesNotMatch(r.node('#app').innerHTML,/NaN|Infinity/);
  creationClick(r,{action:'topic-allocation-details'});
  assert.match(r.node('#app').innerHTML,/아직 배정된 학습 일정이 없어요/);
});

test('an overdue unknown record still highlights the replan action and explains that its learning result is needed', async () => {
  const r=runtime();r.run("events=[{id:'past-unknown-only',planId:'engineer',type:'study',date:'2026-11-24',start:'09:00',end:'10:00',title:'미확인 학습',content:'',status:'unknown',progress:0}]");
  await r.run("openPlanDetail('engineer')");
  assert.match(r.node('#app').innerHTML,/replan-remaining-button active/);
  assert.doesNotMatch(r.node('#app').innerHTML,/data-action="replan-remaining" disabled/);
  r.run('openRemainingReplan(selectedPlan)');
  assert.equal(r.run('planCreation'),null);
  assert.match(r.node('#toast').textContent,/학습 결과를 먼저 기록/);
});

// Run the real application event handlers with a small DOM adapter. This checks
// the full pointer sequence, including errors that preview-only checks miss.
function runtime() {
  const nodes = new Map(), timers = new Map();
  const created=[];
  let hitDate = '2026-11-26', nextTimer = 0;
  function node(key) {
    if (!nodes.has(key)) {
      const classes = new Set();
      nodes.set(key, {
        dataset: {}, handlers: {}, innerHTML: '', textContent: '', clientHeight: 892,
        offsetWidth: 52, style: {setProperty(name,value) {this[name]=value;}},
        classList: {
          add: value => classes.add(value), remove: value => classes.delete(value),
          contains: value => classes.has(value),
          toggle(value, enabled) { enabled ? classes.add(value) : classes.delete(value); }
        },
        querySelector: node, querySelectorAll: () => [],
        addEventListener(name, handler) {
          const previous=this.handlers[name];
          this.handlers[name]=name==='close' && previous ? (...args)=>{previous(...args);return handler(...args);} : handler;
        },
        setAttribute() {}, removeAttribute() {}, remove() {this.removed=true;}, focus() {}, append(...children) {this.children=children;},
        insertAdjacentHTML(position, html) { this.inserted = html; },
        showModal() { this.open = true; }, close() { this.open = false; this.handlers.close?.(); },
        setPointerCapture(id) { this.captured = id; },
        hasPointerCapture(id) { return this.captured === id; },
        releasePointerCapture() { this.captured = null; },
        cloneNode() { return node('floating-ghost'); }
      });
    }
    return nodes.get(key);
  }
  const document = {
    handlers: {}, querySelector: node, body: node('body'),
    createElement() {const element=node(`created-${created.length}`);created.push(element);return element;},
    addEventListener(name, handler) { this.handlers[name] = handler; },
    elementFromPoint() {
      if (!hitDate) return null;
      const day = node(`day-${hitDate}`); day.dataset.dropDate = hitDate;
      return {closest: () => day};
    }
  };
  const context = vm.createContext({
    document, window: {addEventListener() {}, matchMedia: () => ({matches: true})},
    getComputedStyle: () => ({transform: 'none', opacity: '1'}), Date, Intl,
    setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
    clearTimeout(id) { timers.delete(id); }, requestAnimationFrame: () => 1,
    cancelAnimationFrame() {}
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8'), context);
  const run = code => vm.runInContext(code, context);
  const app = node('#app');
  let source, lastY = 100, lastX = 120;
  function action(extra = {}) {
    return {target: {closest: () => source}, button: 0, pointerId: 7,
      pointerType: 'mouse', clientX: 100, clientY: 100, preventDefault() {}, ...extra};
  }
  return {
    run, node, created,
    down(id, pointerType = 'mouse') {
      source = node(`source-${id}`); source.dataset.event = id;
      app.handlers.pointerdown(action({pointerType}));
    },
    downReposition(id) {
      source=node(`reposition-source-${id}`);source.dataset.reposition=id;
      app.handlers.pointerdown(action());
    },
    hover(date, y = 100) { hitDate = date; lastY = y; lastX=120; document.handlers.pointermove(action({clientX: 120, clientY:y})); },
    hoverAt(date,x,y) {hitDate=date;lastX=x;lastY=y;document.handlers.pointermove(action({clientX:x,clientY:y}));},
    setHitDate(date) {hitDate=date;},
    edgePending() {return [...timers.values()].some(fn=>fn.name==='pageEdgeDwell');},
    waitEdge() {const entry=[...timers.entries()].find(([,fn])=>fn.name==='pageEdgeDwell');if(!entry)return false;timers.delete(entry[0]);entry[1]();return true;},
    up() { document.handlers.pointerup(action({clientX:lastX,clientY:lastY})); },
    hold() { [...timers.values()].find(fn => fn.name === 'startPointerMove')?.(); },
    choose(date) {
      run('suppressEventClickUntil = 0');
      app.handlers.click({target: {closest: () => ({dataset: {date}})}, preventDefault() {}});
    }
  };
}

test('mouse drag starts, updates hovered date, drops and confirms', async () => {
  const r = runtime();
  r.down('sample-19'); r.hover('2026-11-27');
  assert.equal(r.run('pointerMove.active'), true);
  assert.equal(r.node('#app').captured, 7);
  assert.equal(r.run('liveMove.date'), '2026-11-27');
  r.hover('2026-11-28');
  assert.match(r.node('#move-day-events').innerHTML, /가족 모임/);
  r.up();
  assert.equal(r.run('pendingMove.date'), '2026-11-28');
  assert.equal(r.node('#app').captured, null);
  assert.equal(r.node('#confirm-move').disabled, false);
  assert.equal(r.run("events.find(e => e.id === 'sample-19').date"), '2026-11-26');
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e => e.id === 'sample-19').date"), '2026-11-28');
});

test('all native popups use phone bounds and follow phone repositioning', () => {
  const r=runtime();
  r.node('.phone').getBoundingClientRect=()=>({left:180,top:40,width:412,height:892});
  for(const selector of ['#info','#time-conflict-prompt','#add','#edit-schedule','#edit-exam','#adjust-plan','#calendar-conflicts']){
    r.run(`showPhoneDialog('${selector}')`);
    const dialog=r.node(selector);
    assert.equal(dialog.open,true);
    assert.equal(dialog.style['--dialog-center-x'],'386px');
    assert.equal(dialog.style['--dialog-center-y'],'486px');
    assert.equal(dialog.style['--dialog-phone-width'],'412px');
    assert.equal(dialog.style['--dialog-phone-height'],'892px');
    dialog.close();
  }
  r.node('.phone').getBoundingClientRect=()=>({left:0,top:-20,width:360,height:800});
  r.run('updatePhoneDialogBounds()');
  assert.equal(r.node('#time-conflict-prompt').style['--dialog-center-x'],'180px');
  assert.equal(r.node('#time-conflict-prompt').style['--dialog-center-y'],'380px');
  assert.equal(r.node('#time-conflict-prompt').style['--dialog-phone-width'],'360px');
});

test('popup animations finish before closing and repeated dismissal shares the transition', async () => {
  const r=runtime(),dialog=r.node('#info'),animations=[];
  r.run('window.matchMedia=()=>({matches:false})');
  dialog.animate=(frames,options)=>{
    let finish;
    const animation={finished:new Promise(resolve=>{finish=resolve;}),cancel(){this.cancelled=true;}};
    animations.push({frames,options,animation,finish});return animation;
  };
  r.run("showPhoneDialog('#info')");
  assert.equal(dialog.open,true);
  assert.equal(animations[0].options.duration,240);
  assert.equal(animations[0].frames[0].opacity,0);
  assert.equal(r.node('.phone').classList.contains('popup-shown'),true);
  assert.equal(r.node('#app').inert,true);
  assert.equal(r.node('body').classList.contains('popup-scroll-locked'),true);
  const closing=r.run("closePhoneDialog('#info')");
  assert.equal(animations[0].animation.cancelled,true);
  assert.equal(animations[1].options.duration,180);
  assert.equal(dialog.open,true);
  assert.equal(dialog.classList.contains('popup-closing'),true);
  assert.equal(r.node('#app').inert,true);
  assert.equal(r.node('body').classList.contains('popup-scroll-locked'),true);
  assert.equal(r.run("closePhoneDialog('#info')"),closing);
  assert.equal(r.node('#fab-create-actions').inert,true);
  r.run("globalThis.closingScrollBlocked=false;preventPopupBackgroundScroll({target:{closest:()=>null},preventDefault(){closingScrollBlocked=true;}})");
  assert.equal(r.run('closingScrollBlocked'),true);
  // Even an early native close event must not unlock the still-closing popup.
  dialog.open=false;r.run('unlockPhoneDialogBackground()');
  assert.equal(r.node('#app').inert,true);
  assert.equal(r.node('.phone').classList.contains('popup-interaction-locked'),true);
  animations[1].finish();await closing;
  assert.equal(dialog.open,false);
  assert.equal(dialog.classList.contains('popup-closing'),false);
  assert.equal(r.node('.phone').classList.contains('popup-shown'),false);
  assert.equal(r.node('#app').inert,false);
  assert.equal(r.node('body').classList.contains('popup-scroll-locked'),false);
});

test('popups block background wheel and touch scroll while allowing content scrolling and restore prior locks', async () => {
  const r=runtime(),dialog=r.node('#info');
  dialog.getBoundingClientRect=()=>({left:120,right:492,top:300,bottom:600});
  const send=(target,extra={})=>{
    let blocked=false;
    r.run('preventPopupBackgroundScroll')( {target,preventDefault(){blocked=true;},...extra});
    return blocked;
  };
  const background={closest:()=>null},content={closest:()=>dialog};
  dialog.closest=()=>dialog;
  r.node('#app').inert=true;
  r.run("showPhoneDialog('#info')");
  assert.equal(send(background),true);
  assert.equal(send(content),false);
  assert.equal(send(dialog,{clientX:200,clientY:200}),true);
  assert.equal(send(dialog,{clientX:200,clientY:400}),false);
  assert.equal(send(dialog,{touches:[{clientX:200,clientY:200}]}),true);
  assert.equal(send(dialog,{touches:[{clientX:200,clientY:400}]}),false);
  await r.run("closePhoneDialog('#info')");
  assert.equal(r.node('#app').inert,true);
  assert.equal(send(background),false);
  assert.equal(r.node('body').classList.contains('popup-scroll-locked'),false);
});

test('only dark area taps dismiss popups; Escape and native cancel forms use the close path', async () => {
  const r=runtime(),dialog=r.node('#time-conflict-prompt');
  r.node('.phone').getBoundingClientRect=()=>({left:100,right:512,top:40,bottom:932,width:412,height:892});
  dialog.getBoundingClientRect=()=>({left:120,right:492,top:300,bottom:600});
  const tap=(x,y,downX=x,downY=y)=>{
    dialog.handlers.pointerdown({target:dialog,clientX:downX,clientY:downY});
    dialog.handlers.click({target:dialog,clientX:x,clientY:y});
  };
  r.run("showPhoneDialog('#time-conflict-prompt')");
  tap(200,400);assert.equal(dialog.open,true);
  tap(30,200);assert.equal(dialog.open,true);
  tap(200,200,200,400);assert.equal(dialog.open,true);
  tap(200,200);assert.equal(dialog.open,false);
  assert.equal(r.node('.phone').classList.contains('popup-shown'),false);
  r.run("showPhoneDialog('#time-conflict-prompt')");
  let prevented=false;
  dialog.handlers.cancel({preventDefault(){prevented=true;}});
  assert.equal(prevented,true);assert.equal(dialog.open,false);
  r.run("showPhoneDialog('#info')");
  r.node('#info').handlers.submit({target:{getAttribute:()=> 'dialog'},preventDefault(){},submitter:{value:'cancel'}});
  assert.equal(r.node('#info').open,false);
});

test('exam editing applies only on confirmation and updates the plan deadline without moving study records', async () => {
  const r=runtime(),before=r.run('JSON.stringify(events)');
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{editExam:'engineer'}})}});
  assert.equal(r.node('#edit-exam').open,true);
  assert.equal(r.node('#exam-date-input').value,'2026-11-30');
  assert.equal(r.node('#exam-plan-name').textContent,'정보처리기사 필기');
  r.node('#exam-date-input').value='2026-11-26';
  r.node('#exam-date-input').handlers.input();
  assert.match(r.node('#exam-impact').textContent,/학습 일정 \d+개/);
  await r.node('#cancel-exam').handlers.click();
  assert.equal(r.run("plans.find(p=>p.id==='engineer').examDate"),'2026-11-30');
  r.run("openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-02-30';
  assert.equal(await r.run('saveExamDate()'),false);
  assert.equal(r.node('#edit-exam').open,true);
  assert.match(r.node('#exam-error').textContent,/올바른 시험일/);
  r.node('#exam-date-input').value='2026-12-02';
  await r.node('#exam-form').handlers.submit({preventDefault(){}});
  assert.equal(r.node('#edit-exam').open,false);
  assert.equal(r.run('selectedDate'),'2026-12-02');
  assert.equal(r.run("plans.find(p=>p.id==='engineer').examDate"),'2026-12-02');
  assert.equal(r.run("plans.find(p=>p.id==='sqld').examDate"),'2026-12-03');
  assert.equal(r.run("canMoveToDate(events.find(e=>e.id==='sample-19'),'2026-12-02')"),true);
  assert.equal(r.run("canMoveToDate(events.find(e=>e.id==='sample-19'),'2026-12-03')"),false);
  assert.equal(r.run('JSON.stringify(events)'),before);
  r.run("calendarView='day';renderCalendar()");
  assert.match(r.node('#app').innerHTML,/data-edit-exam="engineer"/);
  r.run("calendarView='week';renderCalendar()");
  assert.match(r.node('#app').innerHTML,/data-edit-exam="engineer"/);
  assert.match(r.node('#plans-list').innerHTML,/2026\.12\.02/);
  assert.equal(runtime().run("plans.find(p=>p.id==='engineer').examDate"),'2026-11-30');
});

test('plan drawer offers exam editing for inactive plans without activating them', async () => {
  const r=runtime();
  r.run("setPlanActive('sqld',false);openPlansDrawer()");
  assert.match(r.node('#plans-list').innerHTML,/data-edit-exam="sqld"/);
  await r.node('#plans-list').handlers.click({target:{closest:()=>({dataset:{editExam:'sqld'}})}});
  assert.equal(r.run('plansDrawerOpen'),false);
  assert.equal(r.node('#edit-exam').open,true);
  r.node('#exam-date-input').value='2026-12-05';
  await r.run('saveExamDate()');
  assert.equal(r.run("plans.find(p=>p.id==='sqld').active"),false);
  assert.equal(r.run("plans.find(p=>p.id==='sqld').examDate"),'2026-12-05');
  assert.equal(r.run("activeExamsOn('2026-12-05').length"),0);
  await r.node('#keep-plan-schedule').handlers.click();
  assert.equal(r.node('#app').inert,false);
});

test('exam adjustment asks first, previews dates and conflicts, and applies only remaining study dates', async () => {
  const r=runtime();
  r.run("events.find(e=>e.id==='sample-20').status='done';const originalStudy=events.map(e=>({...e}));openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-11-27';await r.run('saveExamDate()');
  assert.equal(r.node('#adjust-plan').open,true);
  assert.equal(r.run('pendingPlanAdjustment.items'),null);
  assert.equal(r.run('JSON.stringify(events)===JSON.stringify(originalStudy)'),true);
  assert.equal(r.node('#app').inert,true);
  await r.node('#preview-plan-adjustment').handlers.click();
  assert.equal(r.run('JSON.stringify(events)===JSON.stringify(originalStudy)'),true);
  assert.equal(r.node('#adjust-plan').open,false);
  assert.equal(r.node('#plan-adjustment-preview').hidden,false);
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.node('#app').inert,false);
  assert.match(r.node('#app').innerHTML,/plan-adjustment-event/);
  assert.match(r.node('#plan-calendar-summary').textContent,/시간 겹침/);
  assert.equal(r.run('planCalendarPreview.items.every(i=>i.after.date>=TODAY && i.after.date<=planCalendarPreview.examDate)'),true);
  assert.equal(r.run('planCalendarPreview.items.filter(i=>i.before.date!==i.after.date).every(i=>!byDate(i.before.date).some(e=>e.id===i.id) && byDate(i.after.date).filter(e=>e.id===i.id && e.isPlanAdjustmentPreview).length===1)'),true);
  await r.node('#apply-plan-adjustment').handlers.click();
  assert.equal(r.node('#adjust-plan').open,false);
  assert.equal(r.run("events.some(e=>e.date!==originalStudy.find(o=>o.id===e.id).date)"),true);
  assert.equal(r.run("events.filter(e=>e.type==='calendar'||e.planId!=='engineer'||e.status==='done'||e.date<TODAY).every(e=>JSON.stringify(e)===JSON.stringify(originalStudy.find(o=>o.id===e.id)))"),true);
  assert.equal(r.run("events.every(e=>{const old=originalStudy.find(o=>o.id===e.id);return e.start===old.start && e.end===old.end && e.status===old.status && e.progress===old.progress;})"),true);
  assert.equal(r.run('pendingPlanAdjustment'),null);
  assert.equal(r.run('planCalendarPreview'),null);
  assert.equal(r.node('#plan-adjustment-preview').hidden,true);
  assert.doesNotMatch(r.node('#app').innerHTML,/plan-adjustment-event/);
  assert.equal(r.node('#app').inert,false);
});

test('declining or cancelling a plan adjustment keeps the changed exam and original study schedule', async () => {
  for(const preview of [false,true]){
    const r=runtime(),before=r.run('JSON.stringify(events)');
    r.run("openExamEditor('engineer')");
    r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
    if(preview){
      await r.node('#preview-plan-adjustment').handlers.click();
      r.node('#cancel-plan-adjustment').handlers.click();
    }else await r.node('#keep-plan-schedule').handlers.click();
    assert.equal(r.run('JSON.stringify(events)'),before);
    assert.equal(r.run("plans.find(p=>p.id==='engineer').examDate"),'2026-12-04');
    assert.equal(r.run('pendingPlanAdjustment'),null);
    assert.equal(r.run('planCalendarPreview'),null);
    assert.equal(r.node('#app').inert,false);
  }
});

test('calendar plan preview persists across months, blocks editing, and Escape restores the original view', async () => {
  const r=runtime();r.run("calendarView='day';openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)');
  const proposed=r.run('JSON.stringify(planCalendarPreview.items)');
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{action:'next'}})}});
  assert.equal(r.run('viewDate.getMonth()'),11);
  assert.equal(r.run('JSON.stringify(planCalendarPreview.items)'),proposed);
  assert.match(r.node('#app').innerHTML,/plan-adjustment-event/);
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{event:'sample-18'}})}});
  assert.equal(r.run('selectedEvent'),null);
  r.down('sample-18');r.hover('2026-12-02');
  assert.equal(r.run('pointerMove'),null);
  r.node('#plan-adjustment-preview').handlers.keydown({key:'Escape',preventDefault(){}});
  assert.equal(r.run('calendarView'),'day');
  assert.equal(r.run('selectedDate'),'2026-12-04');
  assert.equal(r.run('planCalendarPreview'),null);
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.equal(r.run("plans.find(p=>p.id==='engineer').examDate"),'2026-12-04');
});

test('preview drag changes only draft dates; repeated drags remain editable and final confirmation commits all changes', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)');
  r.down('sample-19');r.hover('2026-11-28');
  assert.equal(r.run('pointerMove.planAdjustment'),true);
  assert.equal(r.node('#apply-plan-adjustment').disabled,true);
  assert.equal(r.run('pendingMove'),null);
  assert.equal(r.run('JSON.stringify(events)'),before);
  r.up();
  assert.equal(r.run('pendingMove.batchDraft'),true);
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-11-27');
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.node('#confirm-move').handlers.click();
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-11-28');
  assert.equal(r.run("byDate('2026-11-28').filter(e=>e.id==='sample-19').length"),1);
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.equal(r.node('#apply-plan-adjustment').disabled,false);
  // Returning to the original date must keep the item draggable for another adjustment.
  r.down('sample-19');r.hover('2026-11-26');r.up();await r.run('confirmMove()');
  assert.equal(r.run("calendarEvents().find(e=>e.id==='sample-19').isPlanAdjustmentPreview"),true);
  r.down('sample-19');r.hover('2026-12-03');r.up();await r.run('confirmMove()');
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-12-03');
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.node('#apply-plan-adjustment').handlers.click();
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-12-03');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'14:00');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').end"),'16:00');
  assert.equal(r.run('planCalendarPreview'),null);
});

test('preview touch drag enforces date limits and cancelling all changes discards manual adjustments', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)'),draft=r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date");
  for(const date of ['2026-11-24','2026-12-05']){
    r.down('sample-19','touch');r.hold();r.hover(date);
    assert.match(r.node('#move-warning').innerHTML,/옮길 수 없어요/);
    r.up();
    assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),draft);
    assert.equal(r.run('JSON.stringify(events)'),before);
    assert.equal(r.node('#confirm-move').disabled,true);
    await r.run('cancelMove()');
  }
  r.down('sample-19','touch');r.hold();r.hover('2026-12-01');r.up();await r.run('confirmMove()');
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-12-01');
  r.node('#cancel-plan-adjustment').handlers.click();
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.equal(r.run('planCalendarPreview'),null);
  assert.equal(r.run("plans.find(p=>p.id==='engineer').examDate"),'2026-12-04');
});

test('preview drag crosses month edges and Escape cancels only the active drag', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  edgeGeometry(r);
  const before=r.run('JSON.stringify(events)'),draft=r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date");
  r.down('sample-19');r.hoverAt('2026-11-28',400,200);
  r.setHitDate('2026-12-02');assert.equal(r.waitEdge(),true);
  assert.equal(r.run('viewDate.getMonth()'),11);
  assert.equal(r.run('pointerMove.planAdjustment'),true);
  r.run("handleMoveKey({key:'Escape',preventDefault(){}})");
  await r.run('moveClosePromise');
  assert.equal(r.run('pointerMove'),null);
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),draft);
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.equal(r.node('#apply-plan-adjustment').disabled,false);
  r.down('sample-19');r.hover('2026-12-02');r.up();await r.run('confirmMove()');
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-12-02');
});

test('hovering during preview drag shows live daily details and conflicts against projected schedules', async () => {
  const r=runtime();
  r.run("const hospital=events.find(e=>e.title==='병원 방문');hospital.start='20:00';hospital.end='21:00';openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)');
  r.node('#move-preview').hidden=false;
  r.down('sample-19');r.hover('2026-11-28');
  assert.equal(r.node('#move-preview').hidden,false);
  assert.equal(r.node('.phone').classList.contains('previewing-move'),true);
  assert.match(r.node('#move-heading').textContent,/11월 28일/);
  assert.match(r.node('#move-day-events').innerHTML,/가족 모임/);
  assert.match(r.node('#move-warning').innerHTML,/같은 시간에 다른 일정/);
  assert.match(r.node('#move-day-events').innerHTML,/conflicting-move-item/);
  r.hover('2026-11-29');
  assert.match(r.node('#move-heading').textContent,/11월 29일/);
  assert.match(r.node('#move-day-events').innerHTML,/이론 복습/);
  assert.doesNotMatch(r.node('#move-day-events').innerHTML,/가족 모임/);
  assert.match(r.node('#move-warning').innerHTML,/같은 시간에 다른 일정/);
  r.hover('2026-11-27');
  assert.doesNotMatch(r.node('#move-warning').innerHTML,/같은 시간에 다른 일정/);
  assert.doesNotMatch(r.node('#move-day-events').innerHTML,/이론 복습/);
  assert.equal(r.run('JSON.stringify(events)'),before);
  r.up();
  assert.equal(r.node('#plan-drag-details').hidden,true);
  assert.equal(r.node('#move-preview').hidden,false);
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.run('cancelMove()');
  r.node('#cancel-plan-adjustment').handlers.click();
  assert.equal(r.node('#plan-drag-details').hidden,true);
  assert.equal(r.node('.phone').classList.contains('plan-inspecting'),false);
});

test('blocked preview dates still show daily details and rejection, and cancelling a drag clears the panel', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)'),draft=r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date");
  r.node('#move-preview').hidden=false;
  r.down('sample-19');r.hover('2026-11-24');
  assert.equal(r.node('#move-preview').hidden,false);
  assert.match(r.node('#move-day-events').innerHTML,/수업/);
  assert.match(r.node('#move-summary').textContent,/이동할 수 없는 날짜/);
  assert.match(r.node('#move-warning').innerHTML,/과거 날짜/);
  r.hover('2026-12-05');
  assert.match(r.node('#move-heading').textContent,/12월 5일/);
  assert.match(r.node('#move-warning').innerHTML,/시험일 이후/);
  r.run("handleMoveKey({key:'Escape',preventDefault(){}})");
  await r.run('moveClosePromise');
  assert.equal(r.node('#plan-drag-details').hidden,true);
  assert.equal(r.run('pointerMove'),null);
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),draft);
  assert.equal(r.run('JSON.stringify(events)'),before);
});

test('preview drop keeps the same visible move sheet and calendar instead of reopening them', async () => {
  for(const view of ['month']){
    const r=runtime();r.run("openExamEditor('engineer')");
    r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
    await r.node('#preview-plan-adjustment').handlers.click();
    if(view==='day'){
      r.run("startPlanTimeEdit('sample-19')");
      r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
      r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
    }
    r.node('#move-preview').hidden=false;
    r.down('sample-19');r.hover('2026-11-27',view==='day'?400:100);
    assert.equal(r.node('#move-preview').hidden,false);
    assert.equal(r.node('#move-preview').classList.contains('dragging'),true);
    assert.equal(r.run('liveMove.id'),'sample-19');
    let calendarWrites=0,html=r.node('#app').innerHTML,hidden=false;
    const visibility=[];
    Object.defineProperty(r.node('#app'),'innerHTML',{get:()=>html,set:value=>{html=value;calendarWrites++;}});
    Object.defineProperty(r.node('#move-preview'),'hidden',{get:()=>hidden,set:value=>{hidden=value;visibility.push(value);}});
    r.up();
    assert.equal(calendarWrites,0);
    assert.equal(visibility.includes(true),false);
    assert.equal(r.node('#move-preview').classList.contains('dragging'),false);
    assert.equal(r.run('pendingMove.batchDraft'),true);
    assert.equal(r.node('#confirm-move').disabled,false);
    await r.run('cancelMove()');
    assert.equal(r.node('.phone').classList.contains('plan-moving'),false);
    assert.equal(r.run('calendarView'),'month');
  }
});

test('conflicting preview study opens day editing and confirmed times stay staged until final batch confirmation', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)');
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{event:'sample-19'}})}});
  assert.equal(r.run('calendarView'),'day');
  assert.equal(r.run('planTimeEdit.id'),'sample-19');
  assert.equal(r.run('planTimeEdit.before.date'),'2026-11-27');
  assert.equal(r.node('#plan-drag-details').hidden,true);
  r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
  r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
  r.down('sample-19');r.hover('2026-11-27',400);r.up();
  assert.equal(r.run('planTimeEdit.draft.start'),'20:00');
  assert.equal(r.run('pendingMove'),null);
  assert.equal(r.run('calendarView'),'day');
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.start"),'20:00');
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.match(r.node('#plan-calendar-summary').textContent,/미리보기에만 반영/);
  await r.node('#apply-plan-adjustment').handlers.click();
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('planTimeEdit'),null);
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.start"),'20:00');
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.equal(r.run("hasConflict(calendarEvents().find(e=>e.id==='sample-19'))"),false);
  await r.node('#apply-plan-adjustment').handlers.click();
  assert.equal(r.run('planCalendarPreview'),null);
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-27');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'20:00');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').end"),'22:00');
});

test('day time cancellation returns to the plan preview and discards only the time editing session', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)'),staged=r.run('JSON.stringify(planCalendarPreview.items)');
  r.run("showPlanDragDetails(calendarEvents().find(e=>e.id==='sample-19'),'2026-11-27')");
  assert.match(r.node('#plan-drag-day-events').innerHTML,/data-plan-time-edit="sample-19"/);
  r.node('#plan-drag-day-events').handlers.click({target:{closest:()=>({dataset:{planTimeEdit:'sample-19'}})}});
  r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
  r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
  r.down('sample-19');r.hover('2026-11-27',400);r.up();
  await r.node('#cancel-move').handlers.click();
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('planTimeEdit'),null);
  assert.equal(r.node('#plan-adjustment-preview').hidden,false);
  assert.equal(r.node('#plan-drag-details').hidden,false);
  assert.equal(r.run('JSON.stringify(planCalendarPreview.items)'),staged);
  assert.equal(r.run('JSON.stringify(events)'),before);
  r.node('#cancel-plan-adjustment').handlers.click();
  assert.equal(r.run('planCalendarPreview'),null);
  assert.equal(r.run('JSON.stringify(events)'),before);
});

test('manual time editing supports conflicting studies from other plans and never edits existing calendar events', async () => {
  const r=runtime();
  r.run("const sql=events.find(e=>e.planId==='sqld');sql.date='2026-11-27';sql.start='14:00';sql.end='15:00';openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)'),ids=r.run('JSON.stringify(planCalendarPreview.items.map(i=>i.id))');
  r.run("startPlanTimeEdit(events.find(e=>e.title==='병원 방문').id)");
  assert.equal(r.run('planTimeEdit'),null);
  r.run("startPlanTimeEdit(sql.id)");
  assert.equal(r.run('planTimeEdit.addedItem'),true);
  r.node('#cancel-plan-adjustment').handlers.click();
  assert.equal(r.run('JSON.stringify(planCalendarPreview.items.map(i=>i.id))'),ids);
  assert.equal(r.run('JSON.stringify(events)'),before);
});

test('normal move confirmation and cancellation return to the batch preview without touching actual schedules', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");
  r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)');
  r.down('sample-19');r.hover('2026-11-28');r.up();
  assert.equal(r.node('#move-preview').hidden,false);
  assert.equal(r.run('pendingMove.batchDraft'),true);
  r.downReposition('sample-19');r.hover('2026-11-29');r.up();
  assert.equal(r.run('pendingMove.date'),'2026-11-29');
  await r.run('cancelMove()');
  assert.equal(r.run('pendingMove'),null);
  assert.equal(r.node('#move-preview').hidden,true);
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-11-27');
  assert.equal(r.node('#plan-adjustment-preview').hidden,false);
  r.down('sample-19');r.hover('2026-12-02');r.up();await r.run('confirmMove()');
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-12-02');
  r.down('sample-19');r.hover('2026-12-03');r.up();await r.run('cancelMove()');
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-12-02');
  assert.equal(r.node('.phone').classList.contains('plan-moving'),false);
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.node('#apply-plan-adjustment').handlers.click();
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-12-02');
});

test('confirm and cancel synchronize sheet closing, calendar growth and batch controls before restoring the preview', async () => {
  for(const view of ['month']){
    for(const finish of ['confirmMove','cancelMove']){
      const r=runtime();r.run("openExamEditor('engineer')");
      r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
      await r.node('#preview-plan-adjustment').handlers.click();
      if(view==='day'){
        r.run("startPlanTimeEdit('sample-19')");
        r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
        r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
      }
      r.down('sample-19');r.hover(view==='day'?'2026-11-27':'2026-11-28',view==='day'?400:100);r.up();
      const before=r.run('JSON.stringify(events)'),animations=[];
      const surface=r.node(view==='month'?'.grid':'.time-view');
      surface.getBoundingClientRect=()=>({height:view==='month'?291:540});
      for(const [kind,element] of [['sheet',r.node('.move-sheet')],['calendar',surface],['controls',r.node('#plan-adjustment-preview')]]){
        element.animate=(frames,options)=>{
          let resolve;
          const animation={finished:new Promise(done=>{resolve=done;}),cancel(){this.cancelled=true;}};
          animations.push({kind,frames,options,animation,finish:()=>resolve()});return animation;
        };
      }
      r.run('window.matchMedia=()=>({matches:false})');
      const closing=r.run(`${finish}()`);
      assert.equal(r.node('.phone').classList.contains('plan-returning'),true);
      assert.equal(r.node('#app').inert,true);
      assert.equal(animations.length,3);
      assert.equal(animations.every(item=>item.options.duration===340),true);
      const calendar=animations.find(item=>item.kind==='calendar');
      assert.equal(calendar.frames[0].height,view==='month'?'291px':'540px');
      assert.equal(calendar.frames[1].height,view==='month'?'573px':'608px');
      animations.find(item=>item.kind==='sheet').finish();
      await Promise.resolve();await Promise.resolve();
      assert.equal(r.node('#move-preview').hidden,false);
      assert.equal(r.node('.phone').classList.contains('plan-returning'),true);
      animations.filter(item=>item.kind!=='sheet').forEach(item=>item.finish());
      await closing;
      assert.equal(r.node('#move-preview').hidden,true);
      assert.equal(r.node('.phone').classList.contains('plan-moving'),false);
      assert.equal(r.node('.phone').classList.contains('plan-returning'),false);
      assert.equal(r.run('calendarView'),'month');
      assert.equal(r.node('#app').inert,Boolean(r.node('#time-conflict-prompt').open));
      assert.equal(r.run('JSON.stringify(events)'),before);
    }
  }
});

test('final confirm and cancel fade preview pulses and close the panel while restoring responsive calendar height', async () => {
  for(const apply of [false,true]){
    for(const inspect of [false,true]){
      const r=runtime();r.run("openExamEditor('engineer')");
      r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
      await r.node('#preview-plan-adjustment').handlers.click();
      if(inspect)r.run("showPlanDragDetails(calendarEvents().find(e=>e.id==='sample-19'),'2026-11-27')");
      const before=r.run('JSON.stringify(events)'),animations=[],pulse=r.node('preview-pulse');
      r.node('#app').querySelectorAll=selector=>selector==='.plan-adjustment-event'?[pulse]:[];
      r.node('.grid').getBoundingClientRect=()=>({height:inspect?291:573});
      r.node(`created-${r.created.length}`).getBoundingClientRect=()=>({height:448});
      for(const [kind,node] of [['calendar',r.node('.grid')],['panel',r.node('#plan-adjustment-preview')],['pulse',pulse]]){
        node.animate=(frames,options)=>{
          let resolve;
          const animation={finished:new Promise(done=>{resolve=done;}),cancel(){this.cancelled=true;}};
          animations.push({kind,frames,options,animation,finish:()=>resolve()});return animation;
        };
      }
      r.run('window.matchMedia=()=>({matches:false})');
      const closing=apply?r.node('#apply-plan-adjustment').handlers.click():r.node('#cancel-plan-adjustment').handlers.click();
      assert.equal(r.node('.phone').classList.contains('plan-final-closing'),true);
      assert.equal(r.node('#app').inert,true);
      assert.equal(r.node('#plan-adjustment-preview').inert,true);
      assert.equal(r.node('#plan-adjustment-preview').hidden,false);
      assert.equal(r.run('JSON.stringify(events)'),before);
      assert.equal(r.run('cancelPlanAdjustment()'),r.run('planAdjustmentExit'));
      assert.equal(animations.length,3);
      assert.equal(animations.every(item=>item.options.duration===340),true);
      const calendar=animations.find(item=>item.kind==='calendar');
      assert.equal(calendar.frames[0].height,inspect?'291px':'573px');
      assert.equal(calendar.frames[1].height,'448px');
      assert.equal(animations.find(item=>item.kind==='panel').frames[1].transform,'translateY(100%)');
      assert.equal(animations.find(item=>item.kind==='pulse').frames[1].opacity,apply?1:0);
      animations.filter(item=>item.kind!=='pulse').forEach(item=>item.finish());
      await Promise.resolve();await Promise.resolve();
      assert.notEqual(r.run('planCalendarPreview'),null);
      assert.equal(r.run('JSON.stringify(events)'),before);
      animations.find(item=>item.kind==='pulse').finish();await closing;
      assert.equal(r.run('planCalendarPreview'),null);
      assert.equal(r.run('planAdjustmentExit'),null);
      assert.equal(r.node('.phone').classList.contains('plan-final-closing'),false);
      assert.equal(r.node('#plan-adjustment-preview').hidden,true);
      assert.equal(r.node('#app').inert,false);
      assert.equal(r.node('#apply-plan-adjustment').disabled,false);
      assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),apply?'2026-11-27':'2026-11-26');
      if(!apply)assert.equal(r.run('JSON.stringify(events)'),before);
    }
  }
});

test('closed details stay closed and preserve calendar size until drop in normal and batch drag modes', async () => {
  for(const mode of ['normal-month','normal-week','normal-day','batch-month']){
    const r=runtime(),batch=mode.startsWith('batch'),view=mode.split('-')[1];
    if(batch){
      r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
      await r.node('#preview-plan-adjustment').handlers.click();
      if(view==='day')r.run("startPlanTimeEdit('sample-19')");
    }else r.run(`viewDate=parseDate('2026-11-26');selectedDate='2026-11-26';setCalendarView('${view}')`);
    r.node('#move-preview').hidden=true;r.node('#plan-drag-details').hidden=true;
    const before=r.run('JSON.stringify(events)'),gridHeight=r.node('.phone').style['--move-grid-height'];
    const date=view==='day'?(batch?'2026-11-27':'2026-11-26'):'2026-11-27';
    if(view!=='month'){
      r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
      r.node(`day-${date}`).getBoundingClientRect=()=>({top:-800,height:1440});
    }
    r.down('sample-19',batch?'touch':'mouse');if(batch)r.hold();r.hover(date,160);
    assert.equal(r.run('pointerMove.showDetailsDuringDrag'),false);
    assert.equal(r.node('#move-preview').hidden,true);
    assert.equal(r.node('.phone').classList.contains('previewing-move'),false);
    assert.equal(r.node('.phone').style['--move-grid-height'],gridHeight);
    assert.equal(r.node('.phone').classList.contains('plan-moving'),false);
    assert.equal(r.run('JSON.stringify(events)'),before);
    r.up();
    assert.equal(r.node('#move-preview').hidden,false);
    assert.equal(r.node('.phone').classList.contains('previewing-move'),true);
    assert.equal(r.node('#confirm-move').disabled,false);
    assert.equal(r.run('JSON.stringify(events)'),before);
    await r.run('cancelMove()');
    assert.equal(r.run('JSON.stringify(events)'),before);
  }
});

test('re-dragging from an open sheet keeps live details open in normal and batch previews', async () => {
  for(const batch of [false,true]){
    const r=runtime();
    if(batch){
      r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
      await r.node('#preview-plan-adjustment').handlers.click();
    }
    r.down('sample-19');r.hover('2026-11-28');r.up();
    assert.equal(r.node('#move-preview').hidden,false);
    r.downReposition('sample-19');r.hover('2026-11-29');
    assert.equal(r.run('pointerMove.showDetailsDuringDrag'),true);
    assert.equal(r.node('#move-preview').hidden,false);
    assert.match(r.node('#move-heading').textContent,/11월 29일/);
    r.hover('2026-11-28');
    assert.match(r.node('#move-heading').textContent,/11월 28일/);
    assert.match(r.node('#move-day-events').innerHTML,/가족 모임/);
    r.up();await r.run('cancelMove()');
  }
});

test('cancelling a drag without opening details removes temporary calendar markers', async () => {
  const r=runtime(),chip=r.node('unopened-preview-chip');
  r.node('#app').querySelectorAll=selector=>selector==='.move-preview-chip'?[chip]:[];
  r.node('#move-preview').hidden=true;
  r.down('sample-19');r.hover('2026-11-27');chip.removed=false;
  assert.equal(r.node('#move-preview').hidden,true);
  r.run("handleMoveKey({key:'Escape',preventDefault(){}})");
  assert.equal(chip.removed,true);
  assert.equal(r.run('liveMove'),null);
  assert.equal(r.node('#move-preview').hidden,true);
});

test('dragging the move sheet handle folds and expands details without changing the pending move', async () => {
  for(const batch of [false,true]){
    const r=runtime();
    if(batch){
      r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
      await r.node('#preview-plan-adjustment').handlers.click();
    }
    r.down('sample-19');r.hover('2026-11-28');r.up();
    const before=r.run('JSON.stringify(events)'),move=r.run('JSON.stringify(pendingMove)'),handle=r.node('#toggle-move-details');
    r.node('.move-sheet').getBoundingClientRect=()=>({height:446});
    handle.handlers.pointerdown({currentTarget:handle,button:0,pointerId:99,clientY:400,preventDefault(){}});
    r.run('document.handlers.pointermove({pointerId:99,clientY:560,preventDefault(){}})');
    assert.equal(r.node('.phone').classList.contains('sheet-resizing'),true);
    assert.equal(r.node('.move-sheet').style.height,'286px');
    r.run('document.handlers.pointerup({pointerId:99,clientY:560})');
    assert.equal(r.run('moveDetailsCollapsed'),true);
    assert.equal(r.node('#move-preview').classList.contains('details-collapsed'),true);
    assert.equal(r.node('#move-preview').hidden,false);
    assert.equal(r.run('JSON.stringify(pendingMove)'),move);
    assert.equal(r.run('JSON.stringify(events)'),before);
    assert.equal(handle.captured,null);
    r.node('.move-sheet').getBoundingClientRect=()=>({height:176});
    handle.handlers.pointerdown({currentTarget:handle,button:0,pointerId:100,clientY:600,preventDefault(){}});
    r.run('document.handlers.pointermove({pointerId:100,clientY:420,preventDefault(){}})');
    r.run('document.handlers.pointerup({pointerId:100,clientY:420})');
    assert.equal(r.run('moveDetailsCollapsed'),false);
    assert.equal(r.node('.move-sheet').style.height,'');
    assert.equal(r.run('JSON.stringify(pendingMove)'),move);
    assert.equal(r.run('JSON.stringify(events)'),before);
    await r.run('cancelMove()');
    assert.equal(r.run('JSON.stringify(events)'),before);
    if(batch)assert.notEqual(r.run('planCalendarPreview'),null);
  }
});

test('plan preview sheet expansion and folding preserve all staged dates and final confirmation still works', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  r.node('#plan-drag-details').hidden=true;
  const before=r.run('JSON.stringify(events)'),staged=r.run('JSON.stringify(planCalendarPreview.items)'),heading=r.node('#plan-calendar-heading');
  r.node('#plan-adjustment-preview').getBoundingClientRect=()=>({height:164});
  heading.handlers.pointerdown({currentTarget:heading,button:0,pointerId:101,clientY:700,preventDefault(){}});
  r.run('document.handlers.pointermove({pointerId:101,clientY:500,preventDefault(){}})');
  r.run('document.handlers.pointerup({pointerId:101,clientY:500})');
  assert.equal(r.node('#plan-drag-details').hidden,false);
  assert.equal(r.node('.phone').classList.contains('plan-inspecting'),true);
  assert.equal(r.node('.phone').style['--plan-drag-grid-height'],'291px');
  assert.equal(r.run('JSON.stringify(planCalendarPreview.items)'),staged);
  r.node('#plan-adjustment-preview').getBoundingClientRect=()=>({height:446});
  heading.handlers.pointerdown({currentTarget:heading,button:0,pointerId:102,clientY:400,preventDefault(){}});
  r.run('document.handlers.pointermove({pointerId:102,clientY:600,preventDefault(){}})');
  r.run('document.handlers.pointerup({pointerId:102,clientY:600})');
  assert.equal(r.node('#plan-drag-details').hidden,true);
  assert.equal(r.node('#plan-adjustment-preview').hidden,false);
  assert.equal(r.run('JSON.stringify(planCalendarPreview.items)'),staged);
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.node('#apply-plan-adjustment').handlers.click();
  assert.equal(r.run('planCalendarPreview'),null);
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-27');
});

test('a collapsed move sheet stays compact during dragging and expands on drop without changing the actual event', async () => {
  const r=runtime();r.down('sample-19');r.hover('2026-11-27');r.up();
  const before=r.run('JSON.stringify(events)'),handle=r.node('#toggle-move-details');
  handle.handlers.click();
  assert.equal(r.run('moveDetailsCollapsed'),true);
  r.downReposition('sample-19');r.hover('2026-11-28');
  assert.equal(r.run('pointerMove.showDetailsDuringDrag'),false);
  assert.equal(r.run('pointerMove.keepCollapsedSheet'),true);
  assert.equal(r.node('#move-preview').hidden,false);
  assert.equal(r.node('#move-preview').classList.contains('details-collapsed'),true);
  r.up();
  assert.equal(r.run('pendingMove.date'),'2026-11-28');
  assert.equal(r.run('moveDetailsCollapsed'),false);
  assert.match(r.node('#move-day-events').innerHTML,/가족 모임/);
  assert.match(r.node('#move-warning').innerHTML,/같은 시간에 다른 일정/);
  handle.handlers.click();handle.handlers.click();
  assert.equal(r.run('pendingMove.date'),'2026-11-28');
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-28');
});

test('drop expands folded details for normal and batch moves, including blocked dates', async () => {
  for(const batch of [false,true]){
    for(const blocked of [false,true]){
      const r=runtime();
      if(batch){
        r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
        await r.node('#preview-plan-adjustment').handlers.click();
      }
      r.down('sample-19');r.hover('2026-11-27');r.up();
      r.node('#toggle-move-details').handlers.click();
      const before=r.run('JSON.stringify(events)'),date=blocked?(batch?'2026-12-05':'2026-12-01'):'2026-11-28';
      r.downReposition('sample-19');r.hover(date);
      assert.equal(r.run('moveDetailsCollapsed'),true);
      r.up();
      assert.equal(r.run('moveDetailsCollapsed'),false);
      assert.equal(r.node('#move-preview').classList.contains('details-collapsed'),false);
      assert.equal(r.node('#move-preview').hidden,false);
      assert.equal(r.node('#confirm-move').disabled,blocked);
      assert.equal(r.run('JSON.stringify(events)'),before);
      if(blocked)assert.match(r.node('#move-warning').innerHTML,/시험일 이후/);
      else{
        assert.match(r.node('#move-day-events').innerHTML,/가족 모임/);
        assert.match(r.node('#move-warning').innerHTML,/같은 시간에 다른 일정/);
      }
      await r.run('cancelMove()');
    }
  }
});

test('sheet folding and expansion animate the panel and calendar together without changing preview data', async () => {
  for(const mode of ['move-month','move-day','plan']){
    const r=runtime(),kind=mode==='plan'?'plan':'move';
    if(kind==='plan'){
      r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
      await r.node('#preview-plan-adjustment').handlers.click();
      r.node('#plan-drag-details').hidden=true;
    }else r.run(`calendarView='${mode==='move-day'?'day':'month'}';openMovePreview('sample-19','2026-11-27')`);
    const before=r.run('JSON.stringify(events)'),pending=r.run('JSON.stringify(pendingMove)'),staged=r.run('JSON.stringify(planCalendarPreview?.items)');
    const sheet=r.node(kind==='move'?'.move-sheet':'#plan-adjustment-preview'),surface=r.node(mode==='move-day'?'.time-view':'.grid');
    sheet.getBoundingClientRect=()=>({height:kind==='plan'?164:mode==='move-day'?232:446});
    surface.getBoundingClientRect=()=>({height:kind==='plan'?573:mode==='move-day'?540:291});
    const animations=[];
    for(const [label,element] of [['sheet',sheet],['calendar',surface]])element.animate=(frames,options)=>{
      let resolve;
      const animation={finished:new Promise(done=>{resolve=done;}),cancel(){this.cancelled=true;}};
      animations.push({label,frames,options,animation,finish:()=>resolve()});return animation;
    };
    r.run('window.matchMedia=()=>({matches:false})');
    const transition=r.run(`setSheetExpanded('${kind}',${kind==='plan'})`);
    assert.equal(animations.length,2);
    assert.equal(animations[0].options.duration,320);
    assert.equal(animations[1].options.duration,320);
    assert.equal(animations[0].options.easing,animations[1].options.easing);
    assert.equal(animations[0].frames[1].height,kind==='plan'?'446px':'176px');
    assert.equal(animations[1].frames[1].height,kind==='plan'?'291px':mode==='move-day'?'596px':'561px');
    assert.equal(sheet.classList.contains('sheet-interacted'),true);
    animations[0].finish();await Promise.resolve();
    assert.equal(r.node('.phone').classList.contains('sheet-settling'),true);
    animations[1].finish();await transition;
    assert.equal(r.node('.phone').classList.contains('sheet-settling'),false);
    assert.equal(r.run('JSON.stringify(events)'),before);
    assert.equal(r.run('JSON.stringify(pendingMove)'),pending);
    assert.equal(r.run('JSON.stringify(planCalendarPreview?.items)'),staged);
  }
});

test('releasing a sheet drag settles both heights from the current finger position', async () => {
  const r=runtime();r.run("openMovePreview('sample-19','2026-11-27')");
  const sheet=r.node('.move-sheet'),surface=r.node('.grid'),handle=r.node('#toggle-move-details');
  sheet.getBoundingClientRect=()=>({height:446});surface.getBoundingClientRect=()=>({height:291});
  handle.handlers.pointerdown({currentTarget:handle,button:0,pointerId:121,clientY:400,preventDefault(){}});
  r.run('document.handlers.pointermove({pointerId:121,clientY:560,preventDefault(){}})');
  sheet.getBoundingClientRect=()=>({height:286});surface.getBoundingClientRect=()=>({height:451});
  const animations=[];
  for(const element of [sheet,surface])element.animate=(frames,options)=>{
    let resolve;const animation={finished:new Promise(done=>{resolve=done;}),cancel(){}};
    animations.push({frames,options,finish:()=>resolve()});return animation;
  };
  r.run('window.matchMedia=()=>({matches:false});document.handlers.pointerup({pointerId:121,clientY:560})');
  assert.equal(animations[0].frames[0].height,'286px');
  assert.equal(animations[1].frames[0].height,'451px');
  assert.equal(animations[0].frames[1].height,'176px');
  assert.equal(animations[1].frames[1].height,'561px');
  const settling=r.run('sheetResizeMotion.finished');animations.forEach(item=>item.finish());await settling;
  assert.equal(sheet.classList.contains('sheet-interacted'),true);
  assert.equal(r.node('.phone').classList.contains('sheet-resizing'),false);
  assert.equal(r.run('pendingMove.date'),'2026-11-27');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-26');
});

test('drop expansion starts synchronized panel and calendar animations after the dropped content is ready', async () => {
  for(const mode of ['normal-month','normal-week','normal-day','batch-month']){
    for(const folded of [false,true]){
      const r=runtime(),batch=mode.startsWith('batch'),view=mode.split('-')[1],timeline=view!=='month';
      if(batch){
        r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
        await r.node('#preview-plan-adjustment').handlers.click();
        if(timeline)r.run("startPlanTimeEdit('sample-19')");
      }else r.run(`viewDate=parseDate('2026-11-26');selectedDate='2026-11-26';setCalendarView('${view}')`);
      const date=timeline?(view==='week'?'2026-11-27':batch?'2026-11-27':'2026-11-26'):'2026-11-28';
      if(timeline){
        r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
        r.node('reposition-source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
        r.node(`day-${date}`).getBoundingClientRect=()=>({top:-800,height:1440});
      }
      r.node('#move-preview').hidden=true;
      if(folded){
        r.down('sample-19');r.hover(date,timeline?160:100);r.up();
        r.node('#toggle-move-details').handlers.click();
        r.downReposition('sample-19');
      }else r.down('sample-19');
      r.hover(date,timeline?400:100);
      const before=r.run('JSON.stringify(events)'),sheet=r.node('.move-sheet'),surface=r.node(timeline?'.time-view':'.grid'),animations=[];
      const fromSheet=folded?176:0,fromCalendar=folded?(timeline?596:561):(timeline?(batch?608:772):(batch?573:560));
      sheet.getBoundingClientRect=()=>({height:fromSheet});surface.getBoundingClientRect=()=>({height:fromCalendar});
      for(const element of [sheet,surface])element.animate=(frames,options)=>{
        assert.equal(r.node('#move-preview').hidden,false);
        assert.equal(r.node('#move-preview').classList.contains('dragging'),false);
        assert.equal(r.node('#move-preview').classList.contains('details-collapsed'),false);
        let resolve;const animation={finished:new Promise(done=>{resolve=done;}),cancel(){}};
        animations.push({frames,options,finish:()=>resolve()});return animation;
      };
      r.run('window.matchMedia=()=>({matches:false})');r.up();
      assert.equal(animations.length,2);
      assert.equal(animations[0].frames[0].height,`${fromSheet}px`);
      assert.equal(animations[1].frames[0].height,`${fromCalendar}px`);
      assert.equal(animations[0].frames[1].height,timeline?'232px':'446px');
      assert.equal(animations[1].frames[1].height,timeline?'540px':'291px');
      assert.equal(animations[0].options.duration,320);
      assert.equal(animations[1].options.duration,320);
      assert.equal(animations[0].options.easing,animations[1].options.easing);
      assert.equal(r.run('JSON.stringify(events)'),before);
      const transition=r.run('sheetResizeMotion.finished');animations.forEach(item=>item.finish());await transition;
      assert.equal(r.node('.phone').classList.contains('sheet-settling'),false);
      r.run('window.matchMedia=()=>({matches:true})');await r.run('cancelMove()');
    }
  }
});

test('batch move hides the original preview chip through dragging and drop, then restores it on confirm or cancel', async () => {
  for(const confirm of [false,true]){
    const r=runtime();r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
    await r.node('#preview-plan-adjustment').handlers.click();
    const original=r.node('[data-event="sample-19"]'),other=r.node('[data-event="sample-20"]');
    const possibleSources=[original,r.node('source-sample-19'),r.node('reposition-source-sample-19')];
    r.node('#app').querySelectorAll=selector=>selector==='.plan-preview-drag-source'?possibleSources.filter(node=>node.classList.contains('plan-preview-drag-source')):[];
    const before=r.run('JSON.stringify(events)'),staged=r.run('JSON.stringify(planCalendarPreview.items)');
    r.down('sample-19');r.hover('2026-11-28');
    assert.equal(original.classList.contains('plan-preview-drag-source'),true);
    assert.equal(other.classList.contains('plan-preview-drag-source'),false);
    assert.match(r.node('.date-button').inserted,/data-reposition="sample-19"/);
    assert.doesNotMatch(r.node('.date-button').inserted,/plan-preview-drag-source/);
    // Page rendering replaces the source DOM; painting must hide the replacement too.
    original.classList.remove('plan-preview-drag-source');
    r.run("renderCalendar();paintPlanDraftTarget(movePreviewEvent('sample-19'),'2026-11-28')");
    assert.equal(original.classList.contains('plan-preview-drag-source'),true);
    r.up();
    assert.equal(original.classList.contains('plan-preview-drag-source'),true);
    assert.equal(r.run('pendingMove.batchDraft'),true);
    assert.equal(r.run('JSON.stringify(planCalendarPreview.items)'),staged);
    assert.equal(r.run('JSON.stringify(events)'),before);
    await r.run(confirm?'confirmMove()':'cancelMove()');
    assert.equal(original.classList.contains('plan-preview-drag-source'),false);
    assert.equal(other.classList.contains('plan-preview-drag-source'),false);
    assert.equal(r.run('pendingMove'),null);
    assert.equal(r.run("planCalendarPreview.items.find(item=>item.id==='sample-19').after.date"),confirm?'2026-11-28':'2026-11-27');
    assert.equal(r.run('JSON.stringify(events)'),before);
  }
});

test('normal month week and day drags hide the origin until confirmation or cancellation', async () => {
  for(const view of ['month','week','day']){
    for(const confirm of [false,true]){
      const r=runtime();r.run(`viewDate=parseDate('2026-11-26');selectedDate='2026-11-26';setCalendarView('${view}')`);
      const origin=r.node('[data-event="sample-19"]'),other=r.node('[data-event="sample-18"]');
      r.node('#app').querySelectorAll=selector=>selector==='.plan-preview-drag-source' && origin.classList.contains('plan-preview-drag-source')?[origin]:[];
      const before=r.run('JSON.stringify(events)'),date=view==='month'?'2026-11-29':view==='week'?'2026-11-27':'2026-11-26';
      if(view!=='month'){
        r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
        r.node(`day-${date}`).getBoundingClientRect=()=>({top:-800,height:1440});
      }
      r.down('sample-19',view==='day'?'touch':'mouse');if(view==='day')r.hold();
      r.hover(date,view==='month'?100:160);
      assert.equal(origin.classList.contains('plan-preview-drag-source'),true);
      assert.equal(other.classList.contains('plan-preview-drag-source'),false);
      assert.equal(r.run('JSON.stringify(events)'),before);
      r.up();
      assert.equal(origin.classList.contains('plan-preview-drag-source'),true);
      assert.equal(r.run('pendingMove.id'),'sample-19');
      assert.equal(r.run('JSON.stringify(events)'),before);
      await r.run(confirm?'confirmMove()':'cancelMove()');
      assert.equal(origin.classList.contains('plan-preview-drag-source'),false);
      if(confirm)assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),date);
      else assert.equal(r.run('JSON.stringify(events)'),before);
    }
  }
});

test('cancelling a normal drag before dropping restores its hidden origin', async () => {
  const r=runtime(),origin=r.node('[data-event="sample-19"]');
  r.node('#app').querySelectorAll=selector=>selector==='.plan-preview-drag-source' && origin.classList.contains('plan-preview-drag-source')?[origin]:[];
  const before=r.run('JSON.stringify(events)');
  r.down('sample-19');r.hover('2026-11-29');
  assert.equal(origin.classList.contains('plan-preview-drag-source'),true);
  r.run("handleMoveKey({key:'Escape',preventDefault(){}})");
  assert.equal(origin.classList.contains('plan-preview-drag-source'),false);
  assert.equal(r.run('pendingMove'),null);
  assert.equal(r.run('JSON.stringify(events)'),before);
});

test('confirmed batch date conflict offers day time editing and only final batch confirmation changes actual schedules', async () => {
  const r=runtime();r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
  await r.node('#preview-plan-adjustment').handlers.click();
  const before=r.run('JSON.stringify(events)');
  r.down('sample-19');r.hover('2026-11-28');r.up();await r.run('confirmMove()');
  assert.equal(r.node('#time-conflict-prompt').open,true);
  assert.equal(r.run('pendingTimeAdjustment.batchDraft'),true);
  assert.match(r.node('#time-conflict-details').innerHTML,/가족 모임/);
  assert.match(r.node('#time-conflict-description').textContent,/변경 예정 시간/);
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.node('#edit-conflicting-time').handlers.click();
  assert.equal(r.run('calendarView'),'day');
  assert.equal(r.run('planTimeEdit.before.date'),'2026-11-28');
  assert.equal(r.run('pendingMove'),null);
  r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
  r.node('day-2026-11-28').getBoundingClientRect=()=>({top:-800,height:1440});
  r.down('sample-19');r.hover('2026-11-28',400);r.up();await r.run('confirmMove()');
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.node('#time-conflict-prompt').open,false);
  assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.start"),'20:00');
  assert.equal(r.run('JSON.stringify(events)'),before);
  await r.node('#apply-plan-adjustment').handlers.click();
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-28');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'20:00');
});

test('declining batch conflict time editing or cancelling its time drag returns to the staged calendar unchanged', async () => {
  for(const editTime of [false,true]){
    const r=runtime();r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
    await r.node('#preview-plan-adjustment').handlers.click();
    const before=r.run('JSON.stringify(events)');
    r.down('sample-19');r.hover('2026-11-28');r.up();await r.run('confirmMove()');
    if(editTime){
      await r.node('#edit-conflicting-time').handlers.click();
      r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
      r.node('day-2026-11-28').getBoundingClientRect=()=>({top:-800,height:1440});
      r.down('sample-19');r.hover('2026-11-28',400);r.up();await r.run('cancelMove()');
    }else await r.run("closePhoneDialog('#time-conflict-prompt')");
    assert.equal(r.run('calendarView'),'month');
    assert.equal(r.run('planTimeEdit'),null);
    assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.date"),'2026-11-28');
    assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.start"),'14:00');
    assert.equal(r.run('JSON.stringify(events)'),before);
    assert.equal(r.node('#app').inert,false);
  }
});

test('batch conflict prompts include other projected studies and skip clean destinations', async () => {
  for(const conflict of [false,true]){
    const r=runtime();r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
    await r.node('#preview-plan-adjustment').handlers.click();
    r.down('sample-19');r.hover(conflict?'2026-11-29':'2026-12-02');r.up();await r.run('confirmMove()');
    assert.equal(Boolean(r.node('#time-conflict-prompt').open),conflict);
    if(conflict)assert.match(r.node('#time-conflict-details').innerHTML,/이론 복습/);
  }
});

test('conflict day editing can drag existing calendar events, completed studies and unrelated plan studies', async () => {
  for(const type of ['calendar','completed','other-plan']){
    for(const confirm of [false,true]){
      const r=runtime();
      r.run("events.push({id:'completed-day',type:'study',planId:'engineer',title:'완료한 학습',content:'기록 유지',date:'2026-11-27',start:'16:00',end:'17:00',status:'done',progress:100});const sql=events.find(e=>e.planId==='sqld');sql.date='2026-11-27';openExamEditor('engineer')");
      r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
      await r.node('#preview-plan-adjustment').handlers.click();
      const before=r.run('JSON.stringify(events)'),staged=r.run('JSON.stringify(planCalendarPreview.items)');
      r.run("startPlanTimeEdit('sample-19')");
      const id=type==='calendar'?r.run("events.find(e=>e.title==='병원 방문').id"):type==='completed'?'completed-day':r.run('sql.id');
      const originalStatus=r.run(`events.find(e=>e.id==='${id}').status`),originalProgress=r.run(`events.find(e=>e.id==='${id}').progress`);
      assert.equal(r.run(`eventButton(calendarEvents().find(e=>e.id==='${id}')).includes('movable-event')`),true);
      r.node(`source-${id}`).getBoundingClientRect=()=>({top:100,height:58});
      r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
      r.down(id);r.hover('2026-11-27',400);r.up();
      assert.equal(r.run('pendingMove'),null);
      assert.equal(r.run('calendarView'),'day');
      assert.equal(r.run(`planCalendarPreview.items.find(i=>i.id==='${id}').after.start`),'20:00');
      assert.equal(r.run('JSON.stringify(events)'),before);
      await r.run(confirm?'confirmMove()':'cancelMove()');
      assert.equal(r.run('calendarView'),'month');
      assert.equal(r.run('planTimeEdit'),null);
      assert.equal(r.run('JSON.stringify(events)'),before);
      if(confirm){
        assert.equal(r.run(`planCalendarPreview.items.find(i=>i.id==='${id}').after.start`),'20:00');
        await r.node('#apply-plan-adjustment').handlers.click();
        assert.equal(r.run('planCalendarPreview'),null);
        assert.equal(r.run(`events.find(e=>e.id==='${id}').start`),'20:00');
        assert.equal(r.run(`events.find(e=>e.id==='${id}').status`),originalStatus);
        assert.equal(r.run(`events.find(e=>e.id==='${id}').progress`),originalProgress);
      }else assert.equal(r.run('JSON.stringify(planCalendarPreview.items)'),staged);
    }
  }
});

test('normal conflict day allows dragging every displayed event and restores ordinary behavior after confirm or cancel', async () => {
  for(const type of ['calendar','completed','other-plan']){
    for(const confirm of [false,true]){
      const r=runtime();
      r.run("events.push({id:'completed-normal-day',type:'study',planId:'engineer',title:'완료한 학습',content:'기록 유지',date:'2026-11-27',start:'16:00',end:'17:00',status:'done',progress:100});const sql=events.find(e=>e.planId==='sqld');sql.date='2026-11-27';openMovePreview('sample-19','2026-11-27')");
      await r.run('confirmMove()');
      assert.equal(r.node('#time-conflict-prompt').open,true);
      await r.node('#edit-conflicting-time').handlers.click();
      assert.equal(r.run('normalConflictDay.date'),'2026-11-27');
      const before=r.run('JSON.stringify(events)');
      const id=type==='calendar'?r.run("events.find(e=>e.title==='병원 방문').id"):type==='completed'?'completed-normal-day':r.run('sql.id');
      const originalStart=r.run(`events.find(e=>e.id==='${id}').start`),status=r.run(`events.find(e=>e.id==='${id}').status`),progress=r.run(`events.find(e=>e.id==='${id}').progress`);
      assert.equal(r.run(`eventButton(events.find(e=>e.id==='${id}')).includes('movable-event')`),true);
      r.node(`source-${id}`).getBoundingClientRect=()=>({top:100,height:58});
      r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
      r.down(id);r.hover('2026-11-27',400);r.up();
      assert.equal(r.run('pendingMove'),null);
      assert.equal(r.run('calendarView'),'day');
      assert.equal(r.run(`normalConflictDay.items.find(i=>i.id==='${id}').after.start`),'20:00');
      assert.equal(r.run('JSON.stringify(events)'),before);
      await r.run(confirm?'confirmMove()':'cancelMove()');
      assert.equal(r.run('calendarView'),'month');
      assert.equal(r.run('normalConflictDay'),null);
      assert.equal(r.run('pendingMove'),null);
      assert.equal(r.run(`events.find(e=>e.id==='${id}').start`),confirm?'20:00':originalStart);
      assert.equal(r.run(`events.find(e=>e.id==='${id}').status`),status);
      assert.equal(r.run(`events.find(e=>e.id==='${id}').progress`),progress);
      assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'14:00');
      if(!confirm)assert.equal(r.run('JSON.stringify(events)'),before);
      r.run("selectedDate='2026-11-27';viewDate=parseDate(selectedDate);setCalendarView('day')");
      assert.equal(r.run("eventButton(events.find(e=>e.title==='병원 방문')).includes('movable-event')"),false);
    }
  }
});

test('conflict day accumulates repeated edits to multiple events and only final confirm or cancel leaves the day', async () => {
  for(const batch of [false,true]){
    for(const confirm of [false,true]){
      const r=runtime();
      if(batch){
        r.run("openExamEditor('engineer')");r.node('#exam-date-input').value='2026-12-04';await r.run('saveExamDate()');
        await r.node('#preview-plan-adjustment').handlers.click();
        r.run("startPlanTimeEdit('sample-19')");
      }else{
        r.run("openMovePreview('sample-19','2026-11-27')");await r.run('confirmMove()');
        await r.node('#edit-conflicting-time').handlers.click();
      }
      const before=r.run('JSON.stringify(events)'),staged=batch?r.run('JSON.stringify(planTimeEdit.itemsBefore)'):null;
      const hospital=r.run("events.find(e=>e.title==='병원 방문').id");
      r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
      for(const [id,y] of [['sample-19',400],[hospital,310],['sample-19',370]]){
        r.node(`source-${id}`).getBoundingClientRect=()=>({top:100,height:118});
        r.node('.time-scroll').scrollTop=845;
        r.down(id);r.hover('2026-11-27',y);r.up();
        if(r.run('moveClosing'))await r.run('moveClosePromise');
        assert.equal(r.run('calendarView'),'day');
        assert.equal(r.run('pendingMove'),null);
        assert.equal(r.run('JSON.stringify(events)'),before);
        assert.equal(r.node('.time-scroll').scrollTop,845);
      }
      assert.equal(r.run("calendarEvents().find(e=>e.id==='sample-19').start"),'19:30');
      assert.equal(r.run(`calendarEvents().find(e=>e.id==='${hospital}').start`),'18:30');
      if(batch){
        r.node(confirm?'#apply-plan-adjustment':'#cancel-plan-adjustment').handlers.click();
        assert.equal(r.run('calendarView'),'month');
        assert.equal(r.run('planTimeEdit'),null);
        assert.equal(r.run('JSON.stringify(events)'),before);
        if(confirm){
          assert.equal(r.run("planCalendarPreview.items.find(i=>i.id==='sample-19').after.start"),'19:30');
          assert.equal(r.run(`planCalendarPreview.items.find(i=>i.id==='${hospital}').after.start`),'18:30');
          await r.node('#apply-plan-adjustment').handlers.click();
        }else assert.equal(r.run('JSON.stringify(planCalendarPreview.items)'),staged);
      }else{
        r.node(confirm?'#confirm-conflict-session':'#cancel-conflict-session').handlers.click();
        assert.equal(r.run('normalConflictDay'),null);
        assert.equal(r.run('calendarView'),'month');
      }
      if(confirm){
        assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'19:30');
        assert.equal(r.run(`events.find(e=>e.id==='${hospital}').start`),'18:30');
      }else assert.equal(r.run('JSON.stringify(events)'),before);
    }
  }
});

test('unchanged exams and completed plans skip adjustment; past exam dates cannot generate a proposal', async () => {
  const unchanged=runtime();unchanged.run("openExamEditor('engineer')");
  await unchanged.run('saveExamDate()');assert.notEqual(unchanged.node('#adjust-plan').open,true);
  const completed=runtime();completed.run("events.filter(e=>e.planId==='engineer' && e.date>=TODAY).forEach(e=>e.status='done');openExamEditor('engineer')");
  completed.node('#exam-date-input').value='2026-12-04';await completed.run('saveExamDate()');
  assert.notEqual(completed.node('#adjust-plan').open,true);
  const past=runtime();past.run("openExamEditor('engineer')");past.node('#exam-date-input').value='2026-11-24';await past.run('saveExamDate()');
  assert.equal(past.node('#preview-plan-adjustment').disabled,true);
  past.run('previewPlanAdjustment()');assert.equal(past.run('pendingPlanAdjustment.items'),null);
});

test('past and after-exam hover remain inspectable; valid date recovers', async () => {
  const r = runtime();
  r.down('sample-19'); r.hover('2026-11-24');
  assert.equal(r.run('pointerMove.blockedDate'), '2026-11-24');
  assert.match(r.node('#move-day-events').innerHTML, /수업/);
  r.hover('2026-11-30');
  assert.equal(r.run('pointerMove.blockedDate'), null);
  r.hover('2026-12-01'); r.up();
  assert.equal(r.run('pendingMove.blockedDate'), '2026-12-01');
  assert.equal(r.node('#move-preview').hidden, false);
  assert.equal(r.node('#confirm-move').disabled, true);
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e => e.id === 'sample-19').date"), '2026-11-26');
  r.choose('2026-11-30');
  assert.equal(r.node('#confirm-move').disabled, false);
  await r.run('cancelMove()');
  assert.equal(r.run("events.find(e => e.id === 'sample-19').date"), '2026-11-26');
});

test('touch hold starts dragging; past records and calendar events cannot start', () => {
  const r = runtime();
  r.down('sample-14'); assert.equal(r.run('pointerMove'), null);
  r.down('sample-18'); assert.equal(r.run('pointerMove'), null);
  r.down('sample-19', 'touch'); r.hold();
  assert.equal(r.run('pointerMove.active'), true);
  r.hover('2026-11-27'); r.up();
  assert.equal(r.run('pendingMove.date'), '2026-11-27');
});

test('plan switches hide schedules and exams, then restore existing records', () => {
  const r = runtime();
  r.run("events.find(e=>e.id==='sample-19').status='partial';events.find(e=>e.id==='sample-19').progress=65;renderCalendar()");
  const records = r.run('JSON.stringify(events)');
  assert.match(r.node('#app').innerHTML, /data-edit-exam="engineer"/);
  assert.match(r.node('#app').innerHTML, /data-edit-exam="sqld"/);
  r.run("setPlanActive('engineer',false)");
  assert.doesNotMatch(r.node('#app').innerHTML, /data-edit-exam="engineer"/);
  assert.match(r.node('#app').innerHTML, /data-edit-exam="sqld"/);
  assert.equal(r.run("byDate('2026-11-25').length"), 0);
  assert.equal(r.run("byDate('2026-11-26').some(e=>e.planId==='sqld')"), true);
  assert.equal(r.run("byDate('2026-11-26').some(e=>e.type==='calendar')"), true);
  r.run("renderSearch('모의')");
  assert.doesNotMatch(r.node('#search-results').innerHTML, /search-result/);
  r.run("setPlanActive('sqld',false)");
  assert.doesNotMatch(r.node('#app').innerHTML, /data-edit-exam="sqld"/);
  assert.equal(r.run("byDate('2026-11-26').every(e=>e.type==='calendar')"), true);
  r.run("setPlanActive('engineer',true);setPlanActive('sqld',true)");
  assert.equal(r.run('JSON.stringify(events)'), records);
  assert.match(r.node('#plans-list').innerHTML, /role="switch"/);
});

test('hidden plans do not cause conflicts and each plan uses its own exam deadline', () => {
  const r = runtime();
  r.run("Object.assign(events.find(e=>e.id==='sample-19'),{start:'10:00',end:'11:00'})");
  assert.equal(r.run("hasConflict(events.find(e=>e.id==='sample-19'))"), true);
  r.run("setPlanActive('sqld',false)");
  assert.equal(r.run("hasConflict(events.find(e=>e.id==='sample-19'))"), false);
  assert.equal(r.run("canMoveToDate(events.find(e=>e.planId==='engineer'),'2026-12-02')"), false);
  assert.equal(r.run("canMoveToDate(events.find(e=>e.planId==='sqld'),'2026-12-02')"), true);
  assert.match(r.node('#app').innerHTML, /data-action="plans" aria-label="계획 목록"/);
  assert.match(r.node('#app').innerHTML, /data-action="info" aria-label="도움말"/);
});

test('plan drawer slides closed before hiding and keeps switches working', async () => {
  const r = runtime();
  r.run('openPlansDrawer()');
  assert.equal(r.run('plansDrawerOpen'), true);
  assert.equal(r.node('#plans-drawer').hidden, false);
  assert.equal(r.node('#app').inert, true);
  r.run("setPlanActive('sqld',false)");
  assert.equal(r.node('#plans-drawer').hidden, false);
  assert.equal(r.node('#app').inert, true);
  r.run('window.matchMedia=()=>({matches:false})');
  const finish = [];
  for (const id of ['#plans-dialog', '#plans-backdrop']) {
    r.node(id).animate = (frames, options) => {
      assert.equal(options.duration, 280);
      return {finished: new Promise(resolve => finish.push(resolve)), cancel() {}};
    };
  }
  const closing = r.node('#plans-backdrop').handlers.click();
  assert.equal(r.node('#plans-drawer').hidden, false);
  assert.equal(r.node('#app').inert, true);
  assert.equal(r.node('#close-plans').handlers.click(), closing);
  finish.forEach(resolve => resolve());
  await closing;
  assert.equal(r.node('#plans-drawer').hidden, true);
  assert.equal(r.node('#app').inert, false);
  assert.equal(r.run('plansDrawerOpen'), false);
  r.run('window.matchMedia=()=>({matches:true});openPlansDrawer()');
  assert.equal(r.run("plans.find(p=>p.id==='sqld').active"), false);
  await r.run('closePlansDrawer()');
  assert.equal(r.node('#plans-drawer').hidden, true);
});

test('year/month/week/day views render and navigate in the selected unit', () => {
  const r = runtime();
  r.run("setCalendarView('year')");
  assert.equal((r.node('#app').innerHTML.match(/class="mini-month"/g) || []).length, 12);
  assert.match(r.node('#app').innerHTML, /2026년 연간 달력/);
  r.run('navigateCalendar(1)');
  assert.match(r.node('#app').innerHTML, /2027년 연간 달력/);
  r.run("viewDate=parseDate(TODAY);selectedDate=TODAY;setCalendarView('week')");
  assert.match(r.node('#app').innerHTML, /11월 22일 – 11월 28일/);
  assert.equal((r.node('#app').innerHTML.match(/class="time-day-column"/g) || []).length, 7);
  assert.match(r.node('#app').innerHTML, /data-event="sample-19"/);
  r.run('navigateCalendar(1)');
  assert.equal(r.run('dateKey(viewDate)'), '2026-12-02');
  assert.match(r.node('#app').innerHTML, /11월 29일 – 12월 5일/);
  r.run("setCalendarView('day')");
  assert.equal((r.node('#app').innerHTML.match(/class="time-day-column"/g) || []).length, 1);
  r.run('navigateCalendar(-1)');
  assert.equal(r.run('dateKey(viewDate)'), '2026-12-01');
  r.run("setCalendarView('month');navigateCalendar(-1)");
  assert.equal(r.run('dateKey(viewDate)'), '2026-11-01');
  assert.match(r.node('#app').innerHTML, /월간 달력/);
  assert.match(r.node('#calendar-view-options').innerHTML, /data-calendar-view="month" class="calendar-view-choice" aria-pressed="true"/);
});

test('time layouts separate overlapping events and respect plan visibility', () => {
  const r = runtime();
  r.run("viewDate=parseDate('2026-11-26');selectedDate='2026-11-26';setCalendarView('day')");
  const layout = r.run("JSON.stringify(timeEventLayout(byDate('2026-11-26')).map(x=>({id:x.event.id,lane:x.lane,lanes:x.lanes})))");
  const arranged = JSON.parse(layout);
  assert.equal(arranged.find(e=>e.id==='sample-18').lanes,2);
  assert.equal(arranged.find(e=>e.id==='sample-19').lanes,2);
  assert.notEqual(arranged.find(e=>e.id==='sample-18').lane,arranged.find(e=>e.id==='sample-19').lane);
  r.run("setPlanActive('sqld',false)");
  assert.doesNotMatch(r.node('#app').innerHTML,/SQL 기초/);
  assert.match(r.node('#app').innerHTML,/모의 시험/);
  r.run("viewDate=parseDate('2026-12-03');selectedDate='2026-12-03';renderCalendar()");
  assert.doesNotMatch(r.node('#app').innerHTML,/SQLD 시험/);
  r.run("setPlanActive('sqld',true)");
  assert.match(r.node('#app').innerHTML,/SQL 개발자\(SQLD\) 시험일/);
});

test('week drag changes date and time only after before/after confirmation', async () => {
  const r=runtime();
  r.run("viewDate=parseDate('2026-11-26');selectedDate='2026-11-26';setCalendarView('week')");
  r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
  r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
  r.down('sample-19');r.hover('2026-11-27',130);
  assert.equal(r.run('liveMove.start'),'15:30');
  assert.equal(r.run('liveMove.end'),'17:30');
  assert.equal(r.node('#move-time-comparison').hidden,true);
  r.up();
  assert.equal(r.run('pendingMove.date'),'2026-11-27');
  assert.equal(r.run('pendingMove.start'),'15:30');
  assert.equal(r.node('#move-time-comparison').hidden,false);
  assert.match(r.node('#move-time-comparison').innerHTML,/2026.11.26/);
  assert.match(r.node('#move-time-comparison').innerHTML,/2026.11.27/);
  assert.match(r.node('#move-time-comparison').innerHTML,/14:00–16:00/);
  assert.match(r.node('#move-time-comparison').innerHTML,/15:30–17:30/);
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'14:00');
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-27');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'15:30');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').end"),'17:30');
});

test('day drag preserves grab offset and duration, cancel leaves schedule intact', async () => {
  const r=runtime();
  r.run("viewDate=parseDate('2026-11-26');selectedDate='2026-11-26';setCalendarView('day')");
  r.node('source-sample-19').getBoundingClientRect=()=>({top:85,height:118});
  r.node('day-2026-11-26').getBoundingClientRect=()=>({top:-800,height:1440});
  r.down('sample-19');r.hover('2026-11-26',160);r.up();
  assert.equal(r.run('pendingMove.start'),'15:45');
  assert.equal(r.run('pendingMove.end'),'17:45');
  await r.run('cancelMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'14:00');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').end"),'16:00');
  assert.equal(r.run('calendarView'),'day');
});

test('direct day editing stays in day view on confirm and cancel, including after a month-origin edit', async () => {
  for(const fromMonthFirst of [false,true]){
    for(const finish of ['confirmMove','cancelMove']){
      const r=runtime();
      if(fromMonthFirst){
        r.run("openMovePreview('sample-19','2026-11-27')");
        await r.run('confirmMove()');
        await r.node('#edit-conflicting-time').handlers.click();
        await r.run('cancelMove()');
        assert.equal(r.run('calendarView'),'month');
      }
      const date=r.run("events.find(e=>e.id==='sample-19').date");
      r.run(`viewDate=parseDate('${date}');selectedDate='${date}';setCalendarView('day')`);
      r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
      r.node(`day-${date}`).getBoundingClientRect=()=>({top:-800,height:1440});
      r.down('sample-19');r.hover(date,310);r.up();
      assert.equal(r.run('pendingMove.returnToMonth'),false);
      await r.run(`${finish}()`);
      assert.equal(r.run('calendarView'),'day');
      assert.equal(r.run('selectedEvent'),null);
      assert.equal(r.run('pendingMove'),null);
      assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),finish==='confirmMove'?'18:30':'14:00');
    }
  }
});

test('timeline snapping stays in the day and date constraints still block confirmation', async () => {
  const r=runtime();
  r.node('edge-column').dataset.dropDate='2026-11-26';
  r.node('edge-column').getBoundingClientRect=()=>({top:0,height:1440});
  assert.equal(r.run("timelineScheduleAt(events.find(e=>e.id==='sample-19'),document.querySelector('edge-column'),-100).start"),'00:00');
  assert.equal(r.run("timelineScheduleAt(events.find(e=>e.id==='sample-19'),document.querySelector('edge-column'),5000).end"),'23:45');
  r.run("viewDate=parseDate('2026-11-26');selectedDate='2026-11-26';setCalendarView('week')");
  r.node('source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
  r.node('day-2026-12-01').getBoundingClientRect=()=>({top:-800,height:1440});
  r.down('sample-19');r.hover('2026-12-01',130);r.up();
  assert.equal(r.node('#confirm-move').disabled,true);
  assert.match(r.node('#move-time-comparison').innerHTML,/15:30–17:30/);
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-26');
});

test('manual conflict correction opens month view in move selection mode', async () => {
  const r=runtime();
  r.run("calendarView='day';openEvent('sample-19')");
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{action:'manual-fix'}})},preventDefault(){}});
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('selectedEvent'),null);
  assert.equal(r.run('pendingMove.id'),'sample-19');
  assert.equal(r.run('movePicking'),true);
  assert.equal(r.node('#confirm-move').disabled,true);
  assert.match(r.node('#app').innerHTML,/월간 달력/);
  assert.match(r.node('#move-summary').textContent,/이동할 날짜/);
  assert.equal(r.node('#edit-schedule').open,undefined);
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-26');
  r.choose('2026-11-27');
  assert.equal(r.node('#confirm-move').disabled,false);
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-27');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'14:00');
  assert.equal(r.run('selectedEvent'),'sample-19');
  assert.match(r.node('#app').innerHTML,/2026년 11월 27일/);
});

test('cancelling manual conflict correction keeps the source schedule', async () => {
  const r=runtime();
  r.run("calendarView='week';openEvent('sample-19');startManualMove()");
  r.choose('2026-11-28');
  await r.run('cancelMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-26');
  assert.equal(r.run('pendingMove'),null);
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('selectedEvent'),'sample-19');
  assert.match(r.node('#app').innerHTML,/2026년 11월 26일/);
});

test('manual correction retains detail return through blocked dates and repeated picks', async () => {
  const r=runtime();
  r.run("openEvent('sample-19');startManualMove()");
  r.choose('2026-12-01');
  assert.equal(r.run('pendingMove.returnToDetail'),true);
  r.choose('2026-11-27');
  assert.equal(r.run('pendingMove.returnToDetail'),true);
  r.run('movePicking=true');r.choose('2026-11-28');
  assert.equal(r.run('pendingMove.returnToDetail'),true);
  await r.run('confirmMove()');
  assert.equal(r.run('selectedEvent'),'sample-19');
  assert.match(r.node('#app').innerHTML,/2026년 11월 28일/);
});

test('automatic correction previews in month view and applies back to detail', async () => {
  const r=runtime();
  r.run("calendarView='week';openEvent('sample-19')");
  r.node('#app').handlers.click({target:{closest:()=>({dataset:{action:'auto-fix'}})},preventDefault(){}});
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('selectedEvent'),null);
  assert.equal(r.run('pendingMove.returnToDetail'),true);
  assert.equal(r.run('pendingMove.autoSuggested'),true);
  assert.equal(r.node('#confirm-move').textContent,'적용하기');
  assert.equal(r.node('#move-time-comparison').hidden,false);
  assert.match(r.node('#move-time-comparison').innerHTML,/14:00–16:00/);
  assert.match(r.node('#move-time-comparison').innerHTML,/15:00–17:00/);
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'14:00');
  await r.run('confirmMove()');
  assert.equal(r.run('selectedEvent'),'sample-19');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'15:00');
  assert.match(r.node('#app').innerHTML,/오후 3:00 – 오후 5:00/);
  assert.equal(r.run("hasConflict(events.find(e=>e.id==='sample-19'))"),false);
});

test('automatic correction cancellation returns to unchanged detail and no-slot stays there', async () => {
  const r=runtime();
  const before=r.run('JSON.stringify(events)');
  r.run("openEvent('sample-19');autoFixSchedule()");
  await r.run('cancelMove()');
  assert.equal(r.run('selectedEvent'),'sample-19');
  assert.equal(r.run('JSON.stringify(events)'),before);
  assert.match(r.node('#app').innerHTML,/오후 2:00 – 오후 4:00/);
  r.run("events.push({id:'full-exam-day',type:'calendar',date:'2026-11-30',start:'00:00',end:'23:59',title:'종일 일정',content:''});openEvent('sample-22');autoFixSchedule()");
  assert.equal(r.run('pendingMove'),null);
  assert.equal(r.run('selectedEvent'),'sample-22');
  assert.match(r.node('#toast').textContent,/빈 시간이 없어요/);
});

function edgeGeometry(r) {
  r.node('.phone').getBoundingClientRect=()=>({left:0,right:412,top:0,bottom:892,width:412,height:892});
  for(const selector of ['.grid','.time-scroll'])r.node(selector).getBoundingClientRect=()=>({left:0,right:412,top:155,bottom:446,width:412,height:291});
}

test('edge dwell navigates month, week and day while retaining the dragged source', () => {
  for(const [mode,next] of [['month','2026-12-01'],['week','2026-12-03'],['day','2026-11-27']]){
    const r=runtime();edgeGeometry(r);
    r.run(`viewDate=parseDate('2026-11-26');selectedDate='2026-11-26';setCalendarView('${mode}')`);
    r.node('day-2026-11-26').getBoundingClientRect=()=>({top:-800,height:1440});
    r.node(`day-${next}`).getBoundingClientRect=()=>({top:-800,height:1440});
    r.down('sample-19');r.hoverAt('2026-11-26',400,200);
    assert.equal(r.edgePending(),true);
    assert.equal(r.run('dateKey(viewDate)'),'2026-11-26');
    r.setHitDate(next);r.waitEdge();
    assert.equal(r.run('dateKey(viewDate)'),next);
    assert.equal(r.run('pointerMove.id'),'sample-19');
    assert.equal(r.node('#app').captured,7);
    assert.equal(r.run('pendingMove'),null);
    assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-26');
    assert.equal(r.edgePending(),true);
  }
});

test('leaving an edge cancels dwell, left edge goes back and release clears the timer', () => {
  const r=runtime();edgeGeometry(r);
  r.down('sample-19');r.hoverAt('2026-11-27',400,200);
  r.hoverAt('2026-11-27',200,200);
  assert.equal(r.edgePending(),false);
  assert.equal(r.waitEdge(),false);
  r.hoverAt('2026-11-27',8,200);
  r.setHitDate('2026-10-27');r.waitEdge();
  assert.equal(r.run('dateKey(viewDate)'),'2026-10-01');
  r.up();
  assert.equal(r.edgePending(),false);
  assert.equal(r.node('#drag-page-zones').hidden,true);
  assert.equal(r.node('#confirm-move').disabled,true);
});

test('cross-page cancellation restores original page and confirmation still obeys exam constraints', async () => {
  const r=runtime();edgeGeometry(r);
  r.down('sample-19');r.hoverAt('2026-11-27',400,200);
  r.setHitDate('2026-12-01');r.waitEdge();
  r.hover('2026-11-30',200);r.up();
  assert.equal(r.run('pendingMove.date'),'2026-11-30');
  await r.run('cancelMove()');
  assert.equal(r.run('dateKey(viewDate)'),'2026-11-25');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-26');
  r.down('sample-19');r.hoverAt('2026-11-27',400,200);
  r.setHitDate('2026-12-01');r.waitEdge();r.up();
  assert.equal(r.node('#confirm-move').disabled,true);
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-26');
});

test('edge page animation slides in the chosen direction without moving hit targets', async () => {
  for(const direction of [1,-1]){
    const r=runtime();edgeGeometry(r);
    r.run('window.matchMedia=()=>({matches:false})');
    const frames=[],finish=[];let cloneIndex=0;
    r.node('.grid').animate=()=>{};
    r.node('.grid').style.opacity='';
    r.node('.grid').cloneNode=()=>{
      const page=r.node(`page-clone-${cloneIndex++}`);
      page.animate=(keyframes,options)=>{frames.push({keyframes,options});return {finished:new Promise(resolve=>finish.push(resolve)),cancel(){}};};
      return page;
    };
    r.down('sample-19');r.hoverAt('2026-11-27',direction>0?400:8,200);
    r.setHitDate(direction>0?'2026-12-01':'2026-10-27');r.waitEdge();
    assert.equal(frames.length,2);
    assert.equal(frames[0].keyframes[1].transform,`translateX(${-direction*100}%)`);
    assert.equal(frames[1].keyframes[0].transform,`translateX(${direction*100}%)`);
    assert.equal(frames[1].options.duration,320);
    assert.equal(r.node('#app').captured,7);
    assert.equal(r.node('.grid').style.opacity,'0');
    assert.equal(r.created[0].inert,true);
    finish.forEach(resolve=>resolve());
    await new Promise(resolve=>setImmediate(resolve));
    assert.equal(r.node('.grid').style.opacity,'');
    assert.equal(r.created[0].removed,true);
    assert.equal(r.run('dragPageSlide'),null);
    assert.equal(r.run('pointerMove.active'),true);
  }
});

test('confirmed monthly conflict offers time editing and accepts into day drag mode', async () => {
  const r=runtime();
  r.run("openEvent('sample-19');startManualMove()");r.choose('2026-11-27');
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-27');
  assert.equal(r.node('#time-conflict-prompt').open,true);
  assert.match(r.node('#time-conflict-details').innerHTML,/병원 방문/);
  await r.node('#edit-conflicting-time').handlers.click();
  assert.equal(r.node('#time-conflict-prompt').open,false);
  assert.equal(r.run('calendarView'),'day');
  assert.equal(r.run('selectedEvent'),null);
  assert.equal(r.run('pendingMove'),null);
  assert.equal(r.run('normalConflictDay.items.length'),0);
  assert.equal(r.node('#conflict-session-controls').hidden,false);
  r.node('reposition-source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
  r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
  r.downReposition('sample-19');r.hover('2026-11-27',310);r.up();
  assert.equal(r.run('normalConflictDay.items[0].after.start'),'18:30');
  await r.run('confirmMove()');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'18:30');
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run('selectedEvent'),null);
  assert.equal(r.run('selectedDate'),'2026-11-27');
  assert.equal(r.run("hasConflict(events.find(e=>e.id==='sample-19'))"),false);
});

test('cancelling prompted day time editing returns to month and keeps committed date and time', async () => {
  for(const dragFirst of [false,true]){
    const r=runtime();r.run("openMovePreview('sample-19','2026-11-27')");
    await r.run('confirmMove()');
    await r.node('#edit-conflicting-time').handlers.click();
    if(dragFirst){
      r.node('reposition-source-sample-19').getBoundingClientRect=()=>({top:100,height:118});
      r.node('day-2026-11-27').getBoundingClientRect=()=>({top:-800,height:1440});
      r.downReposition('sample-19');r.hover('2026-11-27',310);r.up();
      assert.equal(r.run('normalConflictDay.items[0].after.start'),'18:30');
    }
    await r.run('cancelMove()');
    assert.equal(r.run('calendarView'),'month');
    assert.equal(r.run('selectedEvent'),null);
    assert.equal(r.run('selectedDate'),'2026-11-27');
    assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-27');
    assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'14:00');
  }
});

test('declining time editing keeps the committed monthly move; no overlap skips prompt', async () => {
  const r=runtime();r.run("openMovePreview('sample-19','2026-11-27')");
  await r.run('confirmMove()');
  r.node('#time-conflict-prompt').close();
  assert.equal(r.run('pendingTimeAdjustment'),null);
  assert.equal(r.run('calendarView'),'month');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').date"),'2026-11-27');
  assert.equal(r.run("events.find(e=>e.id==='sample-19').start"),'14:00');
  const clean=runtime();clean.run("openMovePreview('sample-19','2026-11-29')");
  await clean.run('confirmMove()');
  assert.equal(clean.node('#time-conflict-prompt').open,undefined);
  assert.equal(clean.run('pendingTimeAdjustment'),null);
});

test('day and week move previews stay compact and mark overlaps on the timeline', async () => {
  for(const view of ['day','week']){
    const r=runtime();
    r.run(`calendarView='${view}';viewDate=parseDate('2026-11-27');renderCalendar()`);
    const cell=r.node('[data-drop-date="2026-11-27"]');
    cell.querySelector=selector=>selector==='.date-button'?null:r.node(selector);
    const source=r.node('[data-event="sample-19"]');
    const hospitalId=r.run("events.find(e=>e.title==='병원 방문').id");
    const hospital=r.node(`[data-event="${hospitalId}"]`);
    r.node('#app').querySelectorAll=selector=>{
      if(selector==='.time-move-source')return [source].filter(el=>el.classList.contains('time-move-source'));
      if(selector==='.time-move-conflict')return [hospital].filter(el=>el.classList.contains('time-move-conflict'));
      return [];
    };
    r.run("paintMovePreview(events.find(e=>e.id==='sample-19'),'2026-11-27',true,{start:'14:00',end:'16:00'})");
    assert.equal(r.node('#move-preview').classList.contains('timeline-preview'),true);
    assert.equal(r.node('.phone').classList.contains('timeline-dragging'),true);
    assert.equal(r.node('#move-day-events').hidden,true);
    assert.equal(r.node('#move-time-comparison').hidden,true);
    assert.equal(hospital.classList.contains('time-move-conflict'),true);
    assert.match(hospital.inserted,/timeline-conflict-marker/);
    assert.match(cell.inserted,/has-time-overlap/);
    assert.match(cell.inserted,/timeline-conflict-marker/);
    r.run("openMovePreview('sample-19','2026-11-27',{start:'18:30',end:'20:30'})");
    assert.equal(r.node('.phone').classList.contains('timeline-dragging'),false);
    assert.equal(r.node('#move-time-comparison').hidden,false);
    assert.equal(hospital.classList.contains('time-move-conflict'),false);
    assert.doesNotMatch(cell.inserted,/timeline-conflict-marker/);
    await r.run('cancelMove()');
    assert.equal(source.classList.contains('time-move-source'),false);
    assert.equal(r.node('.phone').classList.contains('timeline-move'),false);
    r.run("calendarView='month';openMovePreview('sample-19','2026-11-27')");
    assert.equal(r.node('#move-preview').classList.contains('timeline-preview'),false);
    assert.equal(r.node('#move-day-events').hidden,false);
    assert.match(r.node('#move-day-events').innerHTML,/병원 방문/);
  }
});
