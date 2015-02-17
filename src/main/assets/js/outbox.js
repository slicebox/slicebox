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

.controller('OutboxCtrl', function($scope, $http, $modal, $q) {
    // Initialization
    // $scope.objectActions =
    //     [
    //         {
    //             name: 'Remove',
    //             action: removeBoxes
    //         }
    //     ];

    $scope.callbacks = {};

    $scope.uiState = {
        errorMessage: null
    };
  
    // Scope functions
    $scope.loadOutboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/outbox');
    };
});