(function () {
   'use strict';
}());

angular.module('slicebox.transactions', ['ngRoute'])

.config(function($routeProvider, $mdThemingProvider) {
  $routeProvider.when('/transactions', {
    templateUrl: '/assets/partials/transactions.html',
    controller: 'TransactionsCtrl'
  });
})

.controller('TransactionsCtrl', function($scope) {
    if (!$scope.uiState.incomingTableState) {
        $scope.uiState.incomingTableState = {};
        $scope.uiState.outgoingTableState = {};
        $scope.uiState.boxLogTableState = {};
    }
})

.controller('IncomingCtrl', function($scope, $http, openDeleteEntitiesModalFunction, openTagSeriesModalFunction) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/boxes/incoming/', 'incoming transactions')
            },
            {
                name: 'Tag Series',
                action: openTagSeriesModalFunction('/api/boxes/incoming/')
            }
        ];

    $scope.callbacks = {};

    $scope.loadIncomingPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes/incoming?startindex=' + startIndex + '&count=' + count);
    };

})

.controller('OutgoingCtrl', function($scope, $http, openDeleteEntitiesModalFunction, openTagSeriesModalFunction) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/boxes/outgoing/', 'outgoing transactions')
            },
            {
                name: 'Tag Series',
                action: openTagSeriesModalFunction('/api/boxes/outgoing/')
            }
        ];

    $scope.callbacks = {};

    // Scope functions
    $scope.loadOutgoingPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes/outgoing?startindex=' + startIndex + '&count=' + count);
    };

})

.controller('BoxLogCtrl', function($scope, $http, openDeleteEntitiesModalFunction) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/log/', 'log message(s)')
            }
        ];

    $scope.callbacks = {};

    // Scope functions
    $scope.loadLogPage = function(startIndex, count) {
        return $http.get('/api/log?startindex=' + startIndex + '&count=' + count + '&subject=Box');
    };

});