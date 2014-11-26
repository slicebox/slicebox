'use strict';

angular.module('akkaDcm.view1', ['ngRoute'])

.config(['$routeProvider', function($routeProvider) {
  $routeProvider.when('/view1', {
    templateUrl: '/assets/partials/view1.html',
    controller: 'View1Ctrl'
  });
}])

.controller('View1Ctrl', function($scope, $http) {
  $scope.rows = [];

  $http.get('/api/metadata/list').success(function(data) {
    $scope.rows = data;
  });
});