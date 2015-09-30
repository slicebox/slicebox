(function () {
   'use strict';
}());

angular.module('slicebox.adminPacs', ['ngRoute'])

.config(function($routeProvider) {
    $routeProvider.when('/admin/pacs', {
        templateUrl: '/assets/partials/adminPacs.html',
        controller: 'AdminPacsCtrl'
    });
})

.controller('AdminPacsCtrl', function() {
})

.controller('AdminScpsCtrl', function($scope, $http, openAddEntityModal, openDeleteEntitiesModalFunction) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/scps/', 'SCP(s)')
            }
        ];

    $scope.callbacks = {};

    $scope.uiState.addScpInProgress = false;
  
    // Scope functions
    $scope.loadScpsPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/scps');
    };

    $scope.addScpButtonClicked = function() {
        openAddEntityModal('addScpModalContent.html', 'AddScpModalCtrl', '/api/scps', 'SCP', $scope.callbacks.scpsTable);
    };

})

.controller('AddScpModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ id: -1, name: $scope.name, aeTitle: $scope.aeTitle, port: $scope.port });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
})

.controller('AdminScusCtrl', function($scope, $http, openAddEntityModal, openDeleteEntitiesModalFunction) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/scus/', 'SCU(s)')
            }
        ];

    $scope.callbacks = {};

    $scope.uiState.addScuInProgress = false;
  
    // Scope functions
    $scope.loadScusPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/scus');
    };

    $scope.addScuButtonClicked = function() {
        openAddEntityModal('addScuModalContent.html', 'AddScuModalCtrl', '/api/scus', 'SCU', $scope.callbacks.scusTable);
    };

})

.controller('AddScuModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ id: -1, name: $scope.name, aeTitle: $scope.aeTitle, host: $scope.host, port: $scope.port });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});
