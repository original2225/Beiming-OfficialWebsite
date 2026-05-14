const allowedViews = ['resources', 'vm', 'containers', 'nodes', 'cloud', 'network', 'identity', 'security'];
const allowedResourceViews = ['containers', 'vm', 'nodes'];

export function readRouteState() {
  const params = new URLSearchParams(window.location.search);
  const requestedView = params.get('view') || 'resources';
  let view = allowedViews.includes(requestedView) ? requestedView : 'resources';
  let resourceView = allowedResourceViews.includes(params.get('resource')) ? params.get('resource') : 'containers';
  if (view === 'vm' || view === 'containers') {
    resourceView = view;
    view = 'resources';
  } else if (view === 'nodes') {
    resourceView = 'nodes';
    view = 'resources';
  }
  return {
    view,
    resourceView,
    nodeId: params.get('node') || '',
    containerId: params.get('container') || '',
  };
}

export function updateRouteState({ view, nodeId, containerId, resourceView }) {
  const params = new URLSearchParams();
  if (view) params.set('view', view);
  if (view === 'resources' && allowedResourceViews.includes(resourceView)) params.set('resource', resourceView);
  if (nodeId) params.set('node', nodeId);
  if (containerId) params.set('container', containerId);
  const queryString = params.toString();
  const nextUrl = `${window.location.pathname}${queryString ? `?${queryString}` : ''}`;
  window.history.replaceState(null, '', nextUrl);
}
