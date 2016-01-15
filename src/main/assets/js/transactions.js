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
    $scope.uiState = {};
})

.controller('IncomingCtrl', function($scope, $http, $interval, openDeleteEntitiesModalFunction, openTagSeriesModalFunction) {
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

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.incomingTable)) {
            $scope.callbacks.incomingTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    $scope.loadIncomingPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes/incoming');
    };

})

.controller('OutgoingCtrl', function($scope, $http, $interval, openDeleteEntitiesModalFunction, openTagSeriesModalFunction) {
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

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.outgoingTable)) {
            $scope.callbacks.outgoingTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadOutgoingPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes/outgoing');
    };

})

.controller('BoxLogCtrl', function($scope, $http, $interval, openDeleteEntitiesModalFunction) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/log/', 'log message(s)')
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.logTable)) {
            $scope.callbacks.logTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadLogPage = function(startIndex, count) {
        return $http.get('/api/log?startindex=' + startIndex + '&count=' + count + '&subject=Box');
    };

});