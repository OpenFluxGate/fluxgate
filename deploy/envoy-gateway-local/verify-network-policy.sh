#!/usr/bin/env bash
set -euo pipefail
umask 077
CONTEXT=${FLUXGATE_ENTERPRISE_CONTEXT:-kind-fluxgate-enterprise}
[[ "$CONTEXT" == kind-fluxgate-enterprise ]] || { echo 'Network enforcement proof requires the isolated Calico cluster.' >&2; exit 1; }
EVIDENCE_DIR=${FLUXGATE_NETWORK_EVIDENCE_DIR:-$(mktemp -d /tmp/fluxgate-network-proof.XXXXXX)}
mkdir -p "$EVIDENCE_DIR"
python3 - "$CONTEXT" "$EVIDENCE_DIR" <<'PY'
import ipaddress, json, pathlib, subprocess, sys, uuid
context, proof=sys.argv[1],pathlib.Path(sys.argv[2])
ns='fluxgate-enterprise'
probe_ns='fluxgate-policy-probes'
gateway_ns='envoy-gateway-system'
name='probe-'+uuid.uuid4().hex[:10]
sink=name+'-sink'
gateway_probe=name+'-gateway'
base=['kubectl','--context',context]
def kube(args, **kwargs):
    return subprocess.run(base+args,capture_output=True,text=True,**kwargs)
def read(args):
    return json.loads(kube(args+['-o','json'],check=True).stdout)
def apply(data):
    kube(['apply','-f','-'],input=json.dumps(data),check=True)
def ip(value):
    ipaddress.ip_address(value)
    return value
TCP_CODE='''import ipaddress,json,socket,sys
host,port=sys.argv[1],int(sys.argv[2])
ipaddress.ip_address(host)
try:
    s=socket.create_connection((host,port),3)
    s.close()
except TimeoutError:
    print(json.dumps({'marker':'TCP_CONNECT_TIMEOUT','target':host,'port':port}))
    raise SystemExit(42)
except OSError as error:
    print(json.dumps({'marker':'TCP_CONNECT_ERROR','errno':error.errno,'target':host,'port':port}))
    raise
else:
    print(json.dumps({'marker':'TCP_CONNECTED','target':host,'port':port}))
'''
def connect_python(namespace,pod,host,port):
    result=kube(['-n',namespace,'exec',pod,'--','python','-c',TCP_CODE,ip(host),str(port)],timeout=12)
    try:
        marker=json.loads(result.stdout)
    except (ValueError,TypeError):
        raise RuntimeError(f'Probe execution failed, not a policy decision: {result.returncode} {result.stderr[-300:]}')
    if result.returncode==0 and marker.get('marker')=='TCP_CONNECTED':
        return 'allowed',result
    if result.returncode==42 and marker.get('marker')=='TCP_CONNECT_TIMEOUT':
        return 'blocked',result
    raise RuntimeError(f'DNS/refusal/routing/probe error cannot prove NetworkPolicy: {result.returncode} {marker} {result.stderr[-300:]}')
def connect_authz(host,port):
    result=kube(['-n',ns,'exec',authz,'--','timeout','4','/bin/bash','-c',
                 'exec 3<>/dev/tcp/$1/$2 && printf "AUTHZ_CONNECTED\\n"','probe',ip(host),str(port)],timeout=12)
    if result.returncode==0 and result.stdout.strip()=='AUTHZ_CONNECTED':
        return 'allowed',result
    if result.returncode==124 and not result.stdout.strip() and result.stderr.strip()=='command terminated with exit code 124':
        return 'blocked',result
    raise RuntimeError(f'Actual authz execution/refusal/routing error cannot prove policy: {result.returncode} {result.stdout!r} {result.stderr[-300:]}')
checks=[]
def check(label,outcome,expected):
    actual,result=outcome
    checks.append({'check':label,'expected':expected,'actual':actual,'exit':result.returncode,'marker':result.stdout.strip(),'stderr':result.stderr[-500:]})
    (proof/'network-policy.json').write_text(json.dumps({'context':context,'checks':checks},indent=2)+'\n')
    if actual != expected:
        raise AssertionError(f'{label}: expected {expected}, got {actual}')
def authz_alive():
    result=kube(['-n',ns,'exec',authz,'--','/bin/bash','-c','printf \"AUTHZ_ALIVE\\n\"'],timeout=12)
    assert result.returncode==0 and result.stdout.strip()=='AUTHZ_ALIVE', 'Actual authz source is not alive after negative check'
def probe_alive():
    result=kube(['-n',probe_ns,'exec',name,'--','python','-c','print("PROBE_ALIVE")'],timeout=12)
    assert result.returncode==0 and result.stdout.strip()=='PROBE_ALIVE', 'Untrusted probe is not alive after negative check'
calico=read(['-n','calico-system','get','daemonset','calico-node'])
assert calico['status'].get('numberReady',0)>0, 'Calico node is not Ready'
assert calico['status'].get('numberReady')==calico['status'].get('desiredNumberScheduled')
(proof/'calico-daemonset.json').write_text(json.dumps(calico,indent=2)+'\n')
policy=read(['-n',ns,'get','networkpolicy'])
(proof/'policies.json').write_text(json.dumps(policy,indent=2)+'\n')
pods=read(['-n',ns,'get','pods'])
def ready_pod(app):
    return next(p for p in pods['items'] if p['metadata']['labels'].get('app')==app and p['status'].get('phase')=='Running' and p['status'].get('containerStatuses') and all(c.get('ready') for c in p['status']['containerStatuses']))
authz=ready_pod('fluxgate-authz')['metadata']['name']
apply({'apiVersion':'v1','kind':'Namespace','metadata':{'name':probe_ns,'labels':{'fluxgate.io/environment':'local-proof'}}})
def pod(podname,namespace,args,labels=None):
    return {'apiVersion':'v1','kind':'Pod','metadata':{'name':podname,'namespace':namespace,'labels':labels or {'app':podname}},'spec':{'automountServiceAccountToken':False,'containers':[{'name':'probe','image':'python:3.12-alpine','command':['python']+args,'ports':[{'containerPort':8080}],'resources':{'requests':{'cpu':'10m','memory':'32Mi'},'limits':{'cpu':'100m','memory':'128Mi'}}}]}}
try:
    apply(pod(name,probe_ns,['-c','import time; time.sleep(600)']))
    apply(pod(sink,probe_ns,['-m','http.server','8080']))
    # Administrative fixture in the protected Gateway namespace exercises its allowed identity.
    apply(pod(gateway_probe,gateway_ns,['-c','import time; time.sleep(600)'],{
        'app':gateway_probe,'gateway.envoyproxy.io/owning-gateway-namespace':ns,
        'gateway.envoyproxy.io/owning-gateway-name':'fluxgate-enterprise-gateway'}))
    apply({'apiVersion':'v1','kind':'Service','metadata':{'name':sink,'namespace':probe_ns},'spec':{'selector':{'app':sink},'ports':[{'port':8080}]}})
    kube(['-n',probe_ns,'wait','--for=condition=Ready','pod/'+name,'pod/'+sink,'--timeout=180s'],check=True)
    kube(['-n',gateway_ns,'wait','--for=condition=Ready','pod/'+gateway_probe,'--timeout=180s'],check=True)
    sink_ip=ip(read(['-n',probe_ns,'get','service',sink])['spec']['clusterIP'])
    check('untrusted-probe-to-unrestricted-sink',connect_python(probe_ns,name,sink_ip,8080),'allowed')
    for app,port in [('echo',5678),('fluxgate-authz',8443),('mongo',27017),('redis',6379)]:
        service_ip=ip(read(['-n',ns,'get','service',app])['spec']['clusterIP'])
        pod_ip=ip(ready_pod(app)['status']['podIP'])
        if app in ('mongo','redis'):
            dns=kube(['-n',ns,'exec',authz,'--','getent','ahostsv4',f'{app}.{ns}.svc.cluster.local'],timeout=12)
            assert dns.returncode==0 and service_ip in dns.stdout, f'Actual authz DNS lookup failed for {app}'
        def trusted(target):
            return connect_authz(target,port) if app in ('mongo','redis') else connect_python(gateway_ns,gateway_probe,target,port)
        for address_kind,target in [('service',service_ip),('pod-ip',pod_ip)]:
            label=f'{app}-{address_kind}'
            check('trusted-before-'+label,trusted(target),'allowed')
            check('untrusted-to-'+label,connect_python(probe_ns,name,target,port),'blocked')
            probe_alive()
            check('trusted-after-'+label,trusted(target),'allowed')
    check('untrusted-sink-before-authz-egress-negative',connect_python(probe_ns,name,sink_ip,8080),'allowed')
    check('actual-authz-egress-to-unrestricted-sink',connect_authz(sink_ip,8080),'blocked')
    authz_alive()
    probe_alive()
    check('untrusted-sink-after-authz-egress-negative',connect_python(probe_ns,name,sink_ip,8080),'allowed')
    echo_ip=ip(read(['-n',ns,'get','service','echo'])['spec']['clusterIP'])
    check('gateway-echo-before-authz-bypass-negative',connect_python(gateway_ns,gateway_probe,echo_ip,5678),'allowed')
    check('actual-authz-to-echo-bypass',connect_authz(echo_ip,5678),'blocked')
    authz_alive()
    probe_alive()
    check('gateway-echo-after-authz-bypass-negative',connect_python(gateway_ns,gateway_probe,echo_ip,5678),'allowed')
    print(json.dumps({'context':context,'verified_checks':len(checks),'result':'pass','evidence':str(proof)},indent=2))
finally:
    kube(['-n',probe_ns,'delete','pod',name,sink,'--ignore-not-found=true','--wait=false'])
    kube(['-n',probe_ns,'delete','service',sink,'--ignore-not-found=true'])
    kube(['-n',gateway_ns,'delete','pod',gateway_probe,'--ignore-not-found=true','--wait=false'])
PY
