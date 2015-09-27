(function () {
   'use strict';
}());

angular.module('slicebox.adminForwarding', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/forwarding', {
    templateUrl: '/assets/partials/adminForwarding.html',
    controller: 'AdminForwardingCtrl'
  });
})

.controller('AdminForwardingCtrl', function($scope, $http, $interval, $mdDialog) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: $scope.confirmDeleteEntitiesFunction('/api/forwarding/rules/', 'forwarding rule(s)')
            }
        ];

    $scope.callbacks = {};
  
    // Scope functions
    $scope.loadRulesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/forwarding/rules');
    };

    $scope.addRuleButtonClicked = function() {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/addForwardingRuleModalContent.html',
            controller: 'AddForwardingRuleModalCtrl',
            scope: $scope.$new()
        });
        dialogPromise.then(function (response) {
            $scope.showInfoMessage("Forwarding rule added");                
            $scope.callbacks.rulesTable.reloadPage();
        });
    };
})

.controller('AddForwardingRuleModalCtrl', function($scope, $mdDialog, $http) {

    $scope.uiState = {
        sources: [],
        boxes: [],
        source: null,
        destination: null,
        keepImages: true
    };

    // Scope functions

    $scope.loadSources = function() {
        return $http.get("/api/metadata/sources").success(function (sources) {
            $scope.uiState.sources = sources;
        });
    };

    $scope.loadDestinations = function() {
        return $http.get("/api/metadata/destinations").success(function (destinations) {
            $scope.uiState.destinations = destinations;
        });
    };

    $scope.addButtonClicked = function() {
        var rule = { 
            id: -1,
            source: $scope.uiState.source, 
            destination: $scope.uiState.destination, 
            keepImages: $scope.uiState.keepImages 
        };

        var addRulePromise = $http.post('/api/forwarding/rules', rule);

        addRulePromise.success(function(data) {
            $mdDialog.hide();
        });

        addRulePromise.error(function(data) {
            $scope.showErrorMessage(data);                
        });

        return addRulePromise;
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

});