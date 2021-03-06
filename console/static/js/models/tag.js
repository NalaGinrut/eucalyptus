// tag model
//

define([
    './eucamodel'
], function(EucaModel) {
    var model = EucaModel.extend({
      validation: {
        
        // ====================
        // API Reference: 
        // http://docs.aws.amazon.com/AWSEC2/latest/APIReference/ApiReference-query-CreateTags.html
        // http://docs.aws.amazon.com/AWSEC2/latest/APIReference/ApiReference-query-DeleteTags.html
        // http://docs.aws.amazon.com/AWSEC2/latest/APIReference/ApiReference-query-DescribeTags.html
        // ====================
        
        resource_id: {
          pattern: /^\w{1,3}-\w{8}/,
          required: true
        },
        resource_type: {
          oneOf: [ 'customer-gateway', 'dhcp-options', 'image', 'instance', 'internet-gateway', 'network-acl', 'network-interface', 'reserved-instances', 'route-table', 'security-group', 'snapshot', 'spot-instances-request', 'subnet', 'volume', 'vpc', 'vpn-connection', 'vpn-gateway' ],
          required: false
        },
        key: {
          rangeLength: [1, 128],
          required: true
        },
        value: {
          rangeLength: [0, 256],
          required: false
        },
      }

    });
    return model;
});
