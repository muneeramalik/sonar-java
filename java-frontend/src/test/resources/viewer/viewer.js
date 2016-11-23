function loadDot(DOTstring, isSyntaxTree) {
  var parsedData = vis.network.convertDot(DOTstring);

  var data = {
    nodes: parsedData.nodes,
    edges: parsedData.edges
  }

  var options = parsedData.options;

  options.nodes = {
    color: {
      background: '#eee',
      border: 'gray',
      highlight:{
        background: 'yellow',
        border: 'gold'
      }
    },
    font: {
      size: 12,
      face: 'monospace',
      color: '#333',
      align: 'left'
    }
  }
  if(isSyntaxTree) {
    options.layout = {
      hierarchical: {
        enabled: true,
        sortMethod: 'directed',
        levelSeparation: 100,
        nodeSpacing: 1
      }
    }
  }

  options.edges = {
    font: {
      color: 'grey',
      size: '10'
    }
  }

  var network = new vis.Network(container, data, options);

  network
      .on(
          "selectNode",
          function(params) {
            document.getElementById('programstate').innerHTML = getProgramState(params.nodes);
          });

  network.on("deselectNode", function(params) {
    document.getElementById('programstate').innerHTML = '';
  });

  function getProgramState(nodeIds) {
    if (nodeIds.length == 1) {
      var nodeId = nodeIds[0];
      var programStateAsString;
      data.nodes.forEach(function(node) {
        if (nodeId == node.id) {
          programStateAsString = node.programState;
        }
      })

      var result = '';
      // ugly hack to get differents parts of the program state based on its toString() method. 
      // Should be refactored in order to get correctly each object
      var groups = programStateAsString.split('{');
      if (groups.length == 4) {
        result += '<h3>Program State:</h2>';
        result += '<p><strong>values:</strong><br />' + clean(groups[1])
            + '</p>';
        result += '<p><strong>constraints:</strong><br />' + clean(groups[2])
            + '</p>';
        result += '<p><strong>stack:</strong><br />' + clean(groups[3])
            + '</p>';
      }
      return result;
    }
    return '';
  }

  function clean(value) {
    return value.substring(0, value.indexOf('}'));
  }
};
