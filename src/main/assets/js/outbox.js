(function () {
   'use strict';
}());

angular.module('slicebox.outbox', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/outbox', {
    templateUrl: '/assets/partials/outbox.html',
    controller: 'OutboxCtrl'
  });
})

.controller('OutboxCtrl', function($scope, $http, $modal, $q, $interval) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Remove',
                action: removeOutboxEntries
            }
        ];

    $scope.callbacks = {};

    $scope.uiState = {
        errorMessage: null
    };

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.outboxTable)) {
            $scope.callbacks.outboxTable.reloadPage();
        }
    }, 1000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadOutboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/outbox');
    };

    // Private functions
    function removeOutboxEntries(outboxEntries) {
        var deletePromises = [];
        var deletePromise;
        var deleteAllPromies;

        $scope.uiState.errorMessage = null;

        angular.forEach(outboxEntries, function(outboxEntry) {
            deletePromise = $http.delete('/api/outbox/' + outboxEntry.id);
            deletePromises.push(deletePromise);
        });

        deleteAllPromies = $q.all(deletePromises);

        deleteAllPromies.then(null, function(response) {
            $scope.uiState.errorMessage = response.data;
        });

        return deleteAllPromies;
    }
});