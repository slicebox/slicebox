(function () {
   'use strict';
}());

angular.module('slicebox.inbox', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/inbox', {
    templateUrl: '/assets/partials/inbox.html',
    controller: 'InboxCtrl'
  });
})

.controller('InboxCtrl', function($scope, $http, $modal, $q, $interval) {

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.inboxTable)) {
            $scope.callbacks.inboxTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    $scope.loadInboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/inbox');
    };
});