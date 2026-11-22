"""Recompute counts without confusing legacy completion with visible result evidence."""
import argparse,json,statistics,math
from collections import Counter
from pathlib import Path


def summarize(root, planned=78):
    rows=[]
    for file in sorted(root.glob('*/result.json')):
        record=json.loads(file.read_text());a=record['android'];events=[];taps=[]
        log=file.parent/'device.log'
        for line in log.read_text().splitlines() if log.exists() else []:
            marker='agent.debug.ui_selection '
            if marker in line:
                try:events.append(json.loads(line.split(marker,1)[1]))
                except ValueError:pass
            if 'tap_dispatch method=' in line:taps.append(line.split('tap_dispatch ',1)[1])
        review=file.parent/'screen-review.json'
        review=json.loads(review.read_text()) if review.exists() else {}
        called=[e for e in events if e.get('called') is True]
        held=[e for e in called if e.get('status') in ('abstained','low_confidence')]
        rows.append({'id':file.parent.name,'arm':record['arm'],'task':a['task'],
            'entered':a.get('entry')=='CLICK_home_text_submission','instrumentation_ok':record['instrumentation_ok'],
            'first_guide':a.get('first_visible_guidance_ms'),'first_correct':a.get('first_action_correct'),
            'accepted_goal_type':a.get('accepted_goal_type'),'actual_arrival_ever':'goal_reached_ms' in a,
            'native_goal_final':a.get('native_goal',False),'ready_candidate':a.get('judgment_ready_candidate',False),
            'reviewed_ready':review.get('judgment_ready_success'),
            'judgment_ms':a.get('time_to_judgment_ready_ms'),'failure':a.get('failure_code') or a.get('failure'),
            'jev_calls':len(called),'holds':dict(Counter(e.get('hold_reason') for e in held)),
            'guarded_reobservations':sum(e.get('threshold_reobserve') is True or (record['arm'].startswith('guarded') and e.get('status')=='low_confidence' and (e.get('failure') or {}).get('cause')=='low_confidence' and (e.get('failure') or {}).get('next_action')=='reobserve') for e in called),
            'raw_retry_decisions':sum(e.get('status') in ('abstained','low_confidence') and (e.get('failure') or {}).get('cause')=='settling' for e in called),
            'tap_methods':taps,'final_public_frame':a.get('final_public_frame')})
    def times(v):
        v=sorted(x for x in v if x is not None)
        return {'n':len(v),'median_ms':statistics.median(v) if v else None,'p90_ms':v[max(0,math.ceil(len(v)*.9)-1)] if v else None}
    summary={}
    for arm in ('baseline','retry','guarded','guarded_v2'):
        if not any(r['arm']==arm for r in rows):continue
        allrows=[r for r in rows if r['arm']==arm];rs=[r for r in allrows if r['entered']]
        summary[arm]={'recorded':len(allrows),'entered':len(rs),'setup_rejected':len(allrows)-len(rs),
            'goal_type_connected':sum(bool(r['accepted_goal_type']) for r in rs),
            'first_guidance_visible':sum(r['first_guide'] is not None for r in rs),
            'first_allowed_menu_label_match':sum(r['first_correct'] is True for r in rs),
            'actual_arrival_ever':sum(r['actual_arrival_ever'] for r in rs),
            'ready_candidate':sum(r['ready_candidate'] for r in rs),
            'ready_reviewed':sum(r['reviewed_ready'] is True for r in rs),
            'ready_review_pending':sum(r['ready_candidate'] and r['reviewed_ready'] is None for r in rs),
            'failures':dict(Counter(r['failure'] for r in rs if r['failure'])),
            'first_guidance_times':times([r['first_guide'] for r in rs]),
            'judgment_ready_times':times([r['judgment_ms'] for r in rs if r['ready_candidate']]),
            'jev_calls':sum(r['jev_calls'] for r in rs),'raw_retry_decisions':sum(r['raw_retry_decisions'] for r in rs),'guarded_reobservations':sum(r['guarded_reobservations'] for r in rs)}
    return {'planned':planned,'recorded':len(rows),'arms':summary,'rows':rows}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--planned',type=int,default=78);a=p.parse_args()
    result=summarize(a.root,a.planned);(a.root.parent/(a.root.name+'-summary.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='rows'},ensure_ascii=False,indent=2))
